package com.example.monitoring.risk.scheduler;

import com.example.monitoring.MonitoringApplication;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricUnavailableReason;
import com.example.monitoring.metric.MetricCollectionRecorder;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "T16_NATIVE_ENABLED", matches = "true")
@SpringBootTest(classes = MonitoringApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1", "app.collector.enabled=false",
                "app.metrics.retention-cleanup-enabled=false", "app.part-b.retention-cleanup-enabled=false",
                "app.outbox.publisher-enabled=true", "app.outbox.publish-interval-ms=100",
                "app.outbox.retention-cleanup-enabled=false", "monitoring.risk.enabled=true",
                "monitoring.realtime.enabled=false", "monitoring.notifications.enabled=false",
                "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=${T16_REDIS_PORT:16425}",
                "spring.data.redis.timeout=500ms", "app.redis.stream-key=${T16_STREAM_KEY:stream:metrics}",
                "app.redis.metric-stream-key=stream:t16:legacy-unused",
                "app.database-security.verify-on-startup=false"
        })
@Import(EmbeddedPostgresSupport.Config.class)
@DirtiesContext
class RiskRedisOutageNativeTest {
    private static final long TARGET = 916L;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MetricCollectionRecorder recorder;

    @Test
    void durableACollectionsStayFreshThroughSixtySecondRedisOutageThenRealStopBecomesStale() throws Exception {
        Instant activated = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        seed(activated);
        long initial = record(activated);
        await(() -> acceptedMetric() == initial, 20);
        int port = Integer.parseInt(System.getenv().getOrDefault("T16_REDIS_PORT", "16425"));
        String stream = System.getenv().getOrDefault("T16_STREAM_KEY", "stream:metrics");
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + port);
        try (var connection = client.connect()) {
            assertThat(connection.sync().xlen(stream)).isPositive();
            assertThat(connection.sync().xinfoGroups(stream).toString()).contains("cg:risk");
            assertThat(connection.sync().exists("stream:t16:legacy-unused")).isZero();
            if (!stream.equals("stream:metrics")) {
                assertThat(connection.sync().exists("stream:metrics")).isZero();
            }
            connection.sync().shutdown(false);
        } finally {
            client.shutdown();
        }
        await(() -> !portOpen(port), 10);
        Instant outageStarted = Instant.now();
        long started = System.nanoTime();
        Instant lastRecorded = activated;
        int records = 0;
        Long firstStaleMillis = null;
        while (System.nanoTime() - started < 60_000_000_000L) {
            assertThat(portOpen(port)).isFalse();
            lastRecorded = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            record(lastRecorded);
            records++;
            Thread.sleep(5_000);
            if (staleCount() > 0 && firstStaleMillis == null) {
                firstStaleMillis = (System.nanoTime() - started) / 1_000_000;
            }
        }
        long durationMillis = (System.nanoTime() - started) / 1_000_000;
        long falseStale = staleCount();
        long pendingMetrics = jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE event_type='MetricCollectedEvent' AND published_at IS NULL", Long.class);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("redisUnavailableDurationMillis", durationMillis);
        evidence.put("outageStartedAt", outageStarted.toString());
        evidence.put("metricStream", stream);
        evidence.put("durableARecordsDuringOutage", records);
        evidence.put("pendingMetricOutboxRows", pendingMetrics);
        evidence.put("firstFalseStaleMillis", firstStaleMillis);
        evidence.put("falseStaleIncidentsDuringCollection", falseStale);
        evidence.put("lastDurableAttemptAt", lastRecorded.toString());
        evidence.put("lastConsumedMetricId", acceptedMetric());
        writeEvidence(evidence);
        assertThat(durationMillis).isGreaterThanOrEqualTo(60_000);
        assertThat(records).isGreaterThanOrEqualTo(12);
        assertThat(pendingMetrics).isGreaterThanOrEqualTo(12);
        assertThat(falseStale).as("Redis delivery outage must not imply collection stopped").isZero();
        assertThat(acceptedMetric()).isEqualTo(initial);
        await(() -> staleCount() == 1, 35);
        Instant opened = jdbc.queryForObject("SELECT opened_at FROM incidents WHERE database_config_id=? AND rule_id='COLLECTION_STALE'", Timestamp.class, TARGET).toInstant();
        assertThat(opened).isEqualTo(lastRecorded.plusSeconds(30));
        assertThat(portOpen(port)).isFalse();
        evidence.put("actualCollectorStopOpenedAt", opened.toString());
        evidence.put("actualCollectorStopIncidents", staleCount());
        Files.writeString(Path.of(System.getenv("T16_EVIDENCE_DIR"), "restart-redis"), "restart owned Redis");
        await(() -> portOpen(port), 20);
        await(() -> jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE event_type='MetricCollectedEvent' AND published_at IS NULL", Long.class) == 0, 45);
        Thread.sleep(2_000);
        assertThat(acceptedMetric()).isEqualTo(initial);
        assertThat(jdbc.queryForObject("SELECT status FROM incidents WHERE database_config_id=? AND rule_id='COLLECTION_STALE'", String.class, TARGET)).isEqualTo("OPEN");
        evidence.put("backlogReplayDidNotRecover", true);
        Instant recoveryStarted = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int index = 0; index < 4; index++) {
            if (index > 0) {
                Thread.sleep(5_000);
            }
            long id = record(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            await(() -> acceptedMetric() == id, 10);
            if (index < 3) {
                assertThat(jdbc.queryForObject("SELECT status FROM incidents WHERE database_config_id=? AND rule_id='COLLECTION_STALE'", String.class, TARGET)).isEqualTo("OPEN");
            }
        }
        assertThat(staleCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM incidents WHERE database_config_id=? AND rule_id='COLLECTION_STALE'", String.class, TARGET)).isEqualTo("RESOLVED");
        Instant resolved = jdbc.queryForObject("SELECT resolved_at FROM incidents WHERE database_config_id=? AND rule_id='COLLECTION_STALE'", Timestamp.class, TARGET).toInstant();
        assertThat(resolved).isAfterOrEqualTo(recoveryStarted.plusSeconds(15));
        evidence.put("recoveryStartedAt", recoveryStarted.toString());
        evidence.put("recoveredAfterSustainedSuccessAt", resolved.toString());
        evidence.put("passed", true);
        writeEvidence(evidence);
    }

    private void seed(Instant at) throws Exception {
        jdbc.update("INSERT INTO database_configs (id, collection_interval_seconds, created_at, enabled, host, name, port, status, config_version) VALUES (?,5,?,true,'127.0.0.1','t16',13306,'UNKNOWN',1)", TARGET, Timestamp.from(at));
        jdbc.update("INSERT INTO monitoring_states (database_config_id,config_version,state_version,enabled,deleted,connection_status,data_freshness,activation_at,updated_at) VALUES (?,1,1,true,false,'UNKNOWN','NO_DATA',?,?)", TARGET, Timestamp.from(at), Timestamp.from(at));
        jdbc.update("INSERT INTO risk_policies (database_config_id,version,rules,stale_after_seconds,notification_cooldown_seconds,created_at,updated_at) VALUES (?,1,CAST(? AS jsonb),30,300,?,?)", TARGET, mapper.writeValueAsString(MonitoringContracts.defaultRules()), Timestamp.from(at), Timestamp.from(at));
    }

    private long record(Instant at) {
        Map<String, MetricUnavailableReason> unavailable = new LinkedHashMap<>();
        for (String name : List.of("cpuUsage", "memoryUsage", "qps", "slowQueries", "slowQueriesDelta", "metricWindowSeconds", "threadsRunning", "storageBytes", "responseTimeMs")) {
            unavailable.put(name, MetricUnavailableReason.UNSUPPORTED);
        }
        MetricData metric = MetricData.builder().timestamp(at).collectionAttemptTime(at)
                .collectionStatus(CollectionStatus.SUCCESS).activeConnections(1L).maxConnections(100L)
                .slowQueriesPerSecond(0.0).unavailableMetrics(unavailable).build();
        return recorder.record(new TargetMetadata(TARGET, 1, "t16", "127.0.0.1", 13306, "t16", true), metric).orElseThrow().getId();
    }

    private long acceptedMetric() {
        Long id = jdbc.queryForObject("SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?", Long.class, TARGET);
        return id == null ? 0 : id;
    }

    private long staleCount() {
        return jdbc.queryForObject("SELECT count(*) FROM incidents WHERE database_config_id=? AND rule_id='COLLECTION_STALE'", Long.class, TARGET);
    }

    private boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    private void await(BooleanSupplier ready, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(ready.getAsBoolean()).as("native pipeline condition within %s seconds", seconds).isTrue();
    }

    private void writeEvidence(Map<String, Object> evidence) throws Exception {
        Path destination = Path.of(System.getenv("T16_EVIDENCE_DIR"), "native-outage.json");
        Files.writeString(destination, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
}
