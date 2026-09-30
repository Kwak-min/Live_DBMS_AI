package com.example.monitoring.metric;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.outbox.OutboxEvent;
import com.example.monitoring.common.outbox.OutboxEventRepository;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.collector.MetricSnapshotCalculator;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({EmbeddedPostgresSupport.Config.class, MetricCollectionRecorder.class, OutboxWriter.class,
        UtcInstantJacksonConfig.class})
class MetricCollectionRecorderIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T03:00:00.000Z");
    private static final Path CONTRACT_EXAMPLES = Path.of("..", "docs", "contract-examples.json");

    @Autowired
    private MetricCollectionRecorder recorder;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final MetricSnapshotCalculator calculator = new MetricSnapshotCalculator();

    @Test
    @DisplayName("Success is stored with configVersion and lastSuccessAt, and one MetricCollectedEvent shares its id")
    void successStoresMetricAndOutbox() throws Exception {
        long id = insertTarget("운영 MariaDB", 2, true, false);

        MetricData saved = recorder.record(target(id, 2), success(id, T0)).orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getConfigVersion()).isEqualTo(2L);
        assertThat(saved.getLastSuccessAt()).isEqualTo(T0);

        List<OutboxEvent> events = eventsFor(id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStreamKey()).isEqualTo("stream:metrics");
        JsonNode event = objectMapper.readTree(events.get(0).getPayload());
        assertThat(event.get("eventType").asText()).isEqualTo("MetricCollectedEvent");
        assertThat(event.get("metricId").asLong()).isEqualTo(saved.getId());
        assertThat(event.get("databaseName").asText()).isEqualTo("운영 MariaDB");
        assertThat(event.get("timestamp").asText()).isEqualTo("2026-09-28T03:00:00.000Z");
        assertThat(event.get("unavailableMetrics").get("qps").asText()).isEqualTo("WARMUP");
        assertThat(event.has("host")).isFalse();

        Map<String, Object> config = jdbcTemplate.queryForMap(
                "SELECT status, last_checked_at, last_success_at, updated_at FROM database_configs WHERE id = ?", id);
        assertThat(config.get("status")).isEqualTo("UP");
        assertThat(config.get("last_success_at")).isNotNull();
        assertThat(config.get("updated_at")).isNull();
    }

    @Test
    @DisplayName("Failure keeps the previous lastSuccessAt of the same configVersion and marks the target DOWN")
    void failureKeepsPreviousLastSuccess() {
        long id = insertTarget("db", 1, true, false);
        recorder.record(target(id, 1), success(id, T0)).orElseThrow();

        MetricData failed = recorder.record(target(id, 1), calculator.connectionFailed(
                id, T0.plusSeconds(5), 8, MetricErrorCode.AUTH_FAILED, "대상 DB 인증에 실패했습니다.")).orElseThrow();

        assertThat(failed.getLastSuccessAt()).isEqualTo(T0);
        assertThat(eventsFor(id)).hasSize(2);
        Map<String, Object> config = jdbcTemplate.queryForMap(
                "SELECT status, last_error_message FROM database_configs WHERE id = ?", id);
        assertThat(config.get("status")).isEqualTo("DOWN");
        assertThat(config.get("last_error_message")).isEqualTo("대상 DB 인증에 실패했습니다.");
    }

    @Test
    @DisplayName("Credential failure produces only that target's PARTIAL_FAILURE event and UNKNOWN display status")
    void credentialFailureIsRecorded() {
        long id = insertTarget("bad-key", 1, true, false);
        TargetMetadata summary = new TargetMetadata(id, 1L, "bad-key", "127.0.0.1", 13306, null, true);

        MetricData failed = recorder.record(summary, calculator.internalError(id, T0, 0, true)).orElseThrow();

        assertThat(failed.getCollectionStatus()).isEqualTo(CollectionStatus.PARTIAL_FAILURE);
        assertThat(failed.getErrorCode()).isEqualTo(MetricErrorCode.INTERNAL_ERROR);
        assertThat(failed.getLastSuccessAt()).isNull();
        assertThat(eventsFor(id)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM database_configs WHERE id = ?", String.class, id))
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("A result collected with an old configVersion, or for a disabled/deleted target, is discarded")
    void staleResultsAreDiscarded() {
        long changed = insertTarget("changed", 3, true, false);
        long disabled = insertTarget("disabled", 1, false, false);
        long deleted = insertTarget("deleted", 1, true, true);

        assertThat(recorder.record(target(changed, 2), success(changed, T0))).isEmpty();
        assertThat(recorder.record(target(disabled, 1), success(disabled, T0))).isEmpty();
        assertThat(recorder.record(target(deleted, 1), success(deleted, T0))).isEmpty();

        for (long id : new long[]{changed, disabled, deleted}) {
            assertThat(eventsFor(id)).isEmpty();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM metric_data WHERE database_config_id = ?", Long.class, id)).isZero();
        }
    }

    @Test
    @DisplayName("REST Metric and MetricCollectedEvent JSON have exactly the contract fixture fields")
    void jsonMatchesContractFixtures() throws Exception {
        long id = insertTarget("db", 1, true, false);
        MetricData saved = recorder.record(target(id, 1), success(id, T0)).orElseThrow();
        JsonNode fixtures = objectMapper.readTree(Files.readString(CONTRACT_EXAMPLES)).get("fixtures");

        JsonNode rest = objectMapper.valueToTree(MetricResponseDto.fromEntity(saved));
        JsonNode event = objectMapper.readTree(eventsFor(id).get(0).getPayload());

        assertThat(fieldNames(rest)).containsExactlyInAnyOrderElementsOf(fieldNames(fixtures.get("metricSuccess")));
        assertThat(fieldNames(event))
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixtures.get("metricCollectedEvent")));
    }

    private MetricData success(long id, Instant at) {
        return calculator.connected(id, 0, at, 12,
                Map.of("Threads_connected", "18", "Threads_running", "3", "Queries", "1000",
                        "Slow_queries", "310", "Uptime", "100"),
                Map.of("max_connections", "151"), 104857600L, 0);
    }

    private static CollectorTarget target(long id, long configVersion) {
        return new CollectorTarget(id, configVersion, "db", "127.0.0.1", 13306, null, "u", "p", true);
    }

    private long insertTarget(String name, long configVersion, boolean enabled, boolean deleted) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port, status, config_version, deleted_at)
                VALUES (5, now(), ?, '127.0.0.1', ?, 13306, 'UNKNOWN', ?, CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id
                """, Long.class, enabled, name, configVersion, deleted);
    }

    private List<OutboxEvent> eventsFor(long id) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> ("database:" + id).equals(event.getOrderingKey()))
                .toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
