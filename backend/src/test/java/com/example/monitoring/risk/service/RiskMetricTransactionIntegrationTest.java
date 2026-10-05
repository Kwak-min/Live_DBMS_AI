package com.example.monitoring.risk.service;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.common.outbox.ProcessedEventStore;
import com.example.monitoring.metric.MetricQueryService;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.engine.RiskStateMachine;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskOutboxAppender;
import com.example.monitoring.risk.persistence.StaleCandidate;
import com.example.monitoring.risk.scheduler.RiskStaleTransaction;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({
        EmbeddedPostgresSupport.Config.class,
        UtcInstantJacksonConfig.class,
        OutboxWriter.class,
        ProcessedEventStore.class,
        RiskJdbcStore.class,
        RiskOutboxAppender.class,
        RiskTransitionWriter.class,
        RiskStateMachine.class,
        MetricQueryService.class,
        RiskMetricTransaction.class,
        RiskStaleTransaction.class,
        RiskMetricTransactionIntegrationTest.TimeConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RiskMetricTransactionIntegrationTest {

    private static final long TARGET_ID = 12L;
    private static final Instant ACTIVATED_AT = Instant.parse("2026-10-02T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RiskMetricTransaction transaction;

    @Autowired
    private RiskStaleTransaction staleTransaction;

    @Autowired
    private AdjustableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        seedTargetStateAndPolicy();
        clock.set(ACTIVATED_AT);
    }

    @AfterEach
    void tearDown() {
        dropFailureTrigger();
        cleanup();
    }

    @Test
    void sustainedMetricsCommitDedupIncidentStateAndOrderedOutbox() {
        for (int second : new int[]{0, 5, 10, 15}) {
            long metricId = insertMetric(second, 80L, 100L, 0.0, "SUCCESS");
            clock.set(at(second));
            assertThat(transaction.process("stream:metrics", payload(
                    metricId, 1L, second, 80L, 100L, 0.0, "SUCCESS")))
                    .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM incidents WHERE status='OPEN'", Long.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID)).isEqualTo(5L);
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM event_outbox ORDER BY seq DESC LIMIT 2
                """, String.class)).containsExactly(
                "MonitoringStatusChangedEvent", "IncidentCreatedEvent");

        long lastMetricId = jdbc.queryForObject(
                "SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID);
        MetricCollectedPayloadV1 duplicate = payload(
                lastMetricId, 1L, 15, 80L, 100L, 0.0, "SUCCESS");
        assertThat(transaction.process("stream:metrics", duplicate))
                .isEqualTo(RiskMetricTransaction.Outcome.DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isOne();
    }

    @Test
    void databaseLatestFenceIgnoresBacklogUntilLatestEventArrives() {
        long oldMetric = insertMetric(0, 80L, 100L, 0.0, "SUCCESS");
        long latestMetric = insertMetric(5, 80L, 100L, 0.0, "SUCCESS");

        clock.set(at(5));
        assertThat(transaction.process("stream:metrics", payload(
                oldMetric, 1L, 0, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);
        assertThat(jdbc.queryForObject(
                "SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_rule_states", Long.class)).isZero();

        assertThat(transaction.process("stream:metrics", payload(
                latestMetric, 1L, 5, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        assertThat(jdbc.queryForObject(
                "SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID)).isEqualTo(latestMetric);
        assertThat(jdbc.queryForObject(
                "SELECT warning_candidate_since FROM risk_rule_states "
                        + "WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'",
                Timestamp.class,
                TARGET_ID).toInstant()).isEqualTo(at(5));
    }

    @Test
    void wrongVersionPreActivationReverseOrderAndOverAgeAreInert() {
        long older = insertMetric(0, 10L, 100L, 0.0, "SUCCESS");
        long current = insertMetric(5, 10L, 100L, 0.0, "SUCCESS");
        clock.set(at(5));
        assertThat(transaction.process("stream:metrics", payload(
                current, 1L, 5, 10L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        String baseline = businessSnapshot();

        assertThat(transaction.process("stream:metrics", payload(
                99L, 2L, 10, 95L, 100L, 5.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);
        assertThat(transaction.process("stream:metrics", payload(
                98L, 1L, -1, 95L, 100L, 5.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);
        assertThat(transaction.process("stream:metrics", payload(
                older, 1L, 0, 95L, 100L, 5.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);

        long aged = insertMetric(10, 95L, 100L, 5.0, "SUCCESS");
        clock.set(at(41));
        assertThat(transaction.process("stream:metrics", payload(
                aged, 1L, 10, 95L, 100L, 5.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);
        assertThat(businessSnapshot()).isEqualTo(baseline);
    }

    @Test
    void newestUnreadableCredentialSnapshotMapsToDownAndConnectionFailureCandidate() {
        long metricId = insertConnectionFailedMetric(0);

        assertThat(transaction.process("stream:metrics", connectionFailedPayload(metricId, 0)))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);

        assertThat(jdbc.queryForObject("""
                SELECT connection_status FROM monitoring_states WHERE database_config_id=?
                """, String.class, TARGET_ID)).isEqualTo("DOWN");
        assertThat(jdbc.queryForObject("""
                SELECT fatal_candidate_since FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_FAILURE'
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(0));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE database_config_id=? AND rule_id='CONNECTION_FAILURE'
                """, Long.class, TARGET_ID)).isZero();
    }

    @Test
    void equalAttemptTimeUsesMetricIdTieBreak() {
        long lower = insertMetric(0, 80L, 100L, 0.0, "SUCCESS");
        long higher = insertMetric(0, 80L, 100L, 0.0, "SUCCESS");

        assertThat(transaction.process("stream:metrics", payload(
                lower, 1L, 0, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);
        assertThat(transaction.process("stream:metrics", payload(
                higher, 1L, 0, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        assertThat(jdbc.queryForObject(
                "SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID)).isEqualTo(higher);
    }

    @Test
    void metricRetentionNullsPointersAndStrictlyNewerMetricPreservesOpenHistory() {
        for (int second : new int[]{0, 5, 10, 15}) {
            long metricId = insertMetric(second, 80L, 100L, 0.0, "SUCCESS");
            clock.set(at(second));
            assertThat(transaction.process("stream:metrics", payload(
                    metricId, 1L, second, 80L, 100L, 0.0, "SUCCESS")))
                    .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        }

        long retainedMetricId = jdbc.queryForObject(
                "SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID);
        UUID openIncidentId = jdbc.queryForObject("""
                SELECT incident_id FROM incidents
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO' AND status='OPEN'
                """, UUID.class, TARGET_ID);
        Timestamp openedAt = jdbc.queryForObject("""
                SELECT opened_at FROM incidents WHERE incident_id=?
                """, Timestamp.class, openIncidentId);

        assertThat(jdbc.update("DELETE FROM metric_data WHERE id=?", retainedMetricId)).isOne();

        Map<String, Object> retainedState = jdbc.queryForMap("""
                SELECT last_attempt_at, latest_metric_id
                FROM monitoring_states WHERE database_config_id=?
                """, TARGET_ID);
        assertThat(retainedState.get("last_attempt_at")).isEqualTo(Timestamp.from(at(15)));
        assertThat(retainedState.get("latest_metric_id")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM risk_rule_states
                WHERE database_config_id=? AND last_observed_at=? AND last_metric_id IS NULL
                """, Long.class, TARGET_ID, Timestamp.from(at(15)))).isEqualTo(4L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE incident_id=? AND status='OPEN' AND opened_at=?
                  AND last_observed_at=? AND source_metric_id IS NULL
                """, Long.class, openIncidentId, openedAt, Timestamp.from(at(15)))).isOne();

        long sameTimeMetricId = insertMetric(15, 80L, 100L, 0.0, "SUCCESS");
        clock.set(at(16));
        assertThat(transaction.process("stream:metrics", payload(
                sameTimeMetricId, 1L, 15, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.IGNORED);
        assertThat(jdbc.queryForObject(
                "SELECT latest_metric_id FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID)).isNull();

        long newerMetricId = insertMetric(20, 80L, 100L, 0.0, "SUCCESS");
        clock.set(at(20));
        assertThat(transaction.process("stream:metrics", payload(
                newerMetricId, 1L, 20, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);

        Map<String, Object> advancedState = jdbc.queryForMap("""
                SELECT last_attempt_at, latest_metric_id
                FROM monitoring_states WHERE database_config_id=?
                """, TARGET_ID);
        assertThat(advancedState.get("last_attempt_at")).isEqualTo(Timestamp.from(at(20)));
        assertThat(advancedState.get("latest_metric_id")).isEqualTo(newerMetricId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM risk_rule_states
                WHERE database_config_id=? AND last_observed_at=? AND last_metric_id=?
                """, Long.class, TARGET_ID, Timestamp.from(at(20)), newerMetricId)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE incident_id=? AND status='OPEN' AND opened_at=? AND last_observed_at=?
                """, Long.class, openIncidentId, openedAt, Timestamp.from(at(20)))).isOne();
    }

    @Test
    void delayedFreshMetricAfterStaleOpenDoesNotRewindHistoryAndNeedsFreshSuccessRecovery() {
        assertThat(staleTransaction.process(
                new StaleCandidate(TARGET_ID, at(30)), at(30).plusMillis(700)))
                .isEqualTo(RiskStaleTransaction.Outcome.APPLIED);
        UUID staleIncidentId = jdbc.queryForObject("""
                SELECT incident_id FROM incidents
                WHERE database_config_id=? AND rule_id='COLLECTION_STALE' AND status='OPEN'
                """, UUID.class, TARGET_ID);

        long delayedMetricId = insertMetric(25, 10L, 100L, 0.0, "SUCCESS");
        clock.set(at(31));
        assertThat(transaction.process("stream:metrics", payload(
                delayedMetricId, 1L, 25, 10L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);

        assertThat(jdbc.queryForMap("""
                SELECT data_freshness, risk_level, last_attempt_at, updated_at
                FROM monitoring_states WHERE database_config_id=?
                """, TARGET_ID))
                .containsEntry("data_freshness", "FRESH")
                .containsEntry("risk_level", "CRITICAL")
                .containsEntry("last_attempt_at", Timestamp.from(at(25)))
                .containsEntry("updated_at", Timestamp.from(at(30)));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE incident_id=? AND status='OPEN' AND opened_at=?
                  AND last_observed_at=? AND incident_version=1
                """, Long.class, staleIncidentId, Timestamp.from(at(30)), Timestamp.from(at(30))))
                .isOne();

        long failedMetricId = insertMetric(35, null, null, null, "CONNECTION_FAILED");
        clock.set(at(35));
        assertThat(transaction.process("stream:metrics", payload(
                failedMetricId, 1L, 35, null, null, null, "CONNECTION_FAILED")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);

        for (int second : new int[]{40, 45, 50}) {
            long metricId = insertMetric(second, 10L, 100L, 0.0, "SUCCESS");
            clock.set(at(second));
            assertThat(transaction.process("stream:metrics", payload(
                    metricId, 1L, second, 10L, 100L, 0.0, "SUCCESS")))
                    .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents WHERE incident_id=? AND status='OPEN'
                """, Long.class, staleIncidentId)).isOne();

        long recoveredMetricId = insertMetric(55, 10L, 100L, 0.0, "SUCCESS");
        clock.set(at(55));
        assertThat(transaction.process("stream:metrics", payload(
                recoveredMetricId, 1L, 55, 10L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM incidents
                WHERE incident_id=? AND status='RESOLVED' AND opened_at=?
                  AND last_observed_at=? AND resolved_at=? AND resolution_reason='RECOVERED'
                """, Long.class, staleIncidentId, Timestamp.from(at(30)),
                Timestamp.from(at(55)), Timestamp.from(at(55)))).isOne();
    }

    @Test
    void metricAtExactStaleBoundaryIsAcceptedWithLogicalDueTimestamp() {
        long metricId = insertMetric(0, 80L, 100L, 0.0, "SUCCESS");
        clock.set(at(30));

        assertThat(transaction.process("stream:metrics", payload(
                metricId, 1L, 0, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);

        assertThat(jdbc.queryForObject(
                "SELECT data_freshness FROM monitoring_states WHERE database_config_id=?",
                String.class,
                TARGET_ID)).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("""
                SELECT opened_at FROM incidents
                WHERE database_config_id=? AND rule_id='COLLECTION_STALE' AND status='OPEN'
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(30));
    }

    @Test
    void statusOutboxFailureRollsBackDedupStateClocksAndIncident() {
        long metricId = insertMetric(0, 80L, 100L, 0.0, "SUCCESS");
        String before = completeSnapshot();
        installFailureTrigger();

        assertThatThrownBy(() -> transaction.process(
                "stream:metrics",
                payload(metricId, 1L, 0, 80L, 100L, 0.0, "SUCCESS")))
                .isInstanceOf(RuntimeException.class);

        assertThat(completeSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero();

        dropFailureTrigger();
        assertThat(transaction.process(
                "stream:metrics",
                payload(metricId, 1L, 0, 80L, 100L, 0.0, "SUCCESS")))
                .isEqualTo(RiskMetricTransaction.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isOne();
    }

    private long insertMetric(
            int second,
            Long activeConnections,
            Long maxConnections,
            Double slowRate,
            String status
    ) {
        return jdbc.queryForObject("""
                INSERT INTO metric_data (
                    active_connections, max_connections, slow_queries_per_second,
                    collection_status, created_at, database_config_id, timestamp,
                    config_version, collection_attempt_time, last_success_at,
                    unavailable_metrics
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                activeConnections,
                maxConnections,
                slowRate,
                status,
                Timestamp.from(at(second)),
                TARGET_ID,
                Timestamp.from(at(second)),
                Timestamp.from(at(second)),
                "SUCCESS".equals(status) ? Timestamp.from(at(second)) : null);
    }

    private long insertConnectionFailedMetric(int second) {
        return jdbc.queryForObject("""
                INSERT INTO metric_data (
                    collection_status, created_at, database_config_id, timestamp,
                    config_version, collection_attempt_time, last_success_at,
                    response_time_ms, error_code, error_message, unavailable_metrics
                ) VALUES ('CONNECTION_FAILED', ?, ?, ?, 1, ?, null,
                          0, 'INTERNAL_ERROR', 'database connection failed',
                          CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                Timestamp.from(at(second)),
                TARGET_ID,
                Timestamp.from(at(second)),
                Timestamp.from(at(second)),
                mapper.valueToTree(connectionFailureUnavailable()).toString());
    }

    private MetricCollectedPayloadV1 payload(
            long metricId,
            long configVersion,
            int second,
            Long activeConnections,
            Long maxConnections,
            Double slowRate,
            String status
    ) {
        Instant time = at(second);
        MetricCollectedPayloadV1.CollectionStatus collectionStatus =
                MetricCollectedPayloadV1.CollectionStatus.valueOf(status);
        return new MetricCollectedPayloadV1(
                1,
                new UUID(9L, metricId + configVersion + second + 100L),
                "MetricCollectedEvent",
                time,
                metricId,
                TARGET_ID,
                configVersion,
                "production",
                time,
                time,
                collectionStatus == MetricCollectedPayloadV1.CollectionStatus.SUCCESS ? time : null,
                null,
                null,
                activeConnections,
                maxConnections,
                null,
                null,
                null,
                slowRate,
                5.0,
                null,
                null,
                1L,
                collectionStatus,
                collectionStatus == MetricCollectedPayloadV1.CollectionStatus.SUCCESS
                        ? null : MetricCollectedPayloadV1.MetricErrorCode.QUERY_FAILED,
                collectionStatus == MetricCollectedPayloadV1.CollectionStatus.SUCCESS
                        ? null : "collection failed",
                Map.of());
    }

    private MetricCollectedPayloadV1 connectionFailedPayload(long metricId, int second) {
        Instant time = at(second);
        return new MetricCollectedPayloadV1(
                1,
                new UUID(11L, metricId + 100L),
                "MetricCollectedEvent",
                time,
                metricId,
                TARGET_ID,
                1L,
                "production",
                time,
                time,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                MetricCollectedPayloadV1.CollectionStatus.CONNECTION_FAILED,
                MetricCollectedPayloadV1.MetricErrorCode.INTERNAL_ERROR,
                "database connection failed",
                connectionFailureUnavailable());
    }

    private Map<String, MetricCollectedPayloadV1.UnavailableReason> connectionFailureUnavailable() {
        MetricCollectedPayloadV1.UnavailableReason failed =
                MetricCollectedPayloadV1.UnavailableReason.COLLECTION_FAILED;
        return Map.ofEntries(
                Map.entry("cpuUsage", failed),
                Map.entry("memoryUsage", failed),
                Map.entry("activeConnections", failed),
                Map.entry("maxConnections", failed),
                Map.entry("qps", failed),
                Map.entry("slowQueries", failed),
                Map.entry("slowQueriesDelta", failed),
                Map.entry("slowQueriesPerSecond", failed),
                Map.entry("metricWindowSeconds", failed),
                Map.entry("threadsRunning", failed),
                Map.entry("storageBytes", failed));
    }

    private void seedTargetStateAndPolicy() throws Exception {
        jdbc.update("""
                INSERT INTO database_configs
                    (id, collection_interval_seconds, created_at, enabled, host, name, port, status,
                     config_version, deleted_at)
                VALUES (?, 5, ?, true, '127.0.0.1', 'production', 13306, 'UNKNOWN', 1, null)
                """, TARGET_ID, Timestamp.from(ACTIVATED_AT));
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, risk_level, activation_at, updated_at)
                VALUES (?, 1, 1, true, false, 'UNKNOWN', 'NO_DATA', null, ?, ?)
                """, TARGET_ID, Timestamp.from(ACTIVATED_AT), Timestamp.from(ACTIVATED_AT));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, CAST(? AS jsonb), 30, 300, ?, ?)
                """, TARGET_ID, mapper.writeValueAsString(MonitoringContracts.defaultRules()),
                Timestamp.from(ACTIVATED_AT), Timestamp.from(ACTIVATED_AT));
    }

    private String businessSnapshot() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'state', (SELECT to_jsonb(s) FROM monitoring_states s WHERE database_config_id=12),
                    'clocks', (SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY rule_id), '[]'::jsonb)
                               FROM risk_rule_states r WHERE database_config_id=12),
                    'incidents', (SELECT COALESCE(jsonb_agg(to_jsonb(i) ORDER BY incident_id), '[]'::jsonb)
                                  FROM incidents i WHERE database_config_id=12),
                    'outbox', (SELECT COALESCE(jsonb_agg(to_jsonb(o) ORDER BY seq), '[]'::jsonb)
                               FROM event_outbox o)
                )::text
                """, String.class);
    }

    private String completeSnapshot() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'business', CAST(? AS jsonb),
                    'processed', (SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY event_id), '[]'::jsonb)
                                  FROM processed_events p)
                )::text
                """, String.class, businessSnapshot());
    }

    private void installFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_risk_metric_status_outbox() RETURNS trigger AS $$
                BEGIN
                  IF NEW.event_type = 'MonitoringStatusChangedEvent' THEN
                    RAISE EXCEPTION 'forced metric status outbox failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_risk_metric_status_outbox_trigger
                BEFORE INSERT ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_risk_metric_status_outbox()
                """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_risk_metric_status_outbox_trigger ON event_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_risk_metric_status_outbox()");
    }

    private void cleanup() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM risk_rule_states");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM risk_policies");
        jdbc.update("DELETE FROM monitoring_states");
        jdbc.update("DELETE FROM metric_data");
        jdbc.update("DELETE FROM database_configs");
    }

    private static Instant at(int second) {
        return ACTIVATED_AT.plusSeconds(second);
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        AdjustableClock partCTestClock() {
            return new AdjustableClock(ACTIVATED_AT);
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> now;

        AdjustableClock(Instant initial) {
            now = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
