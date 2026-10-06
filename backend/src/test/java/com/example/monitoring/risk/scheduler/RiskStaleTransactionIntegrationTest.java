package com.example.monitoring.risk.scheduler;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.engine.RiskStateMachine;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskOutboxAppender;
import com.example.monitoring.risk.persistence.StaleCandidate;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import com.example.monitoring.risk.service.RiskTransitionWriter;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({
        EmbeddedPostgresSupport.Config.class,
        UtcInstantJacksonConfig.class,
        OutboxWriter.class,
        RiskJdbcStore.class,
        RiskOutboxAppender.class,
        RiskTransitionWriter.class,
        RiskStateMachine.class,
        RiskStartupCoordinator.class,
        RiskStaleTransaction.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RiskStaleTransactionIntegrationTest {

    private static final long TARGET_ID = 12L;
    private static final UUID INCIDENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000222");
    private static final Instant ACTIVATED_AT = Instant.parse("2026-10-02T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RiskJdbcStore store;

    @Autowired
    private RiskStartupCoordinator startup;

    @Autowired
    private RiskStaleTransaction staleTransaction;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        seedTargetStateAndPolicy();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void startupClearsOnlyCandidateAndRecoveryColumns() {
        long metricId = insertMetric(5);
        jdbc.update("""
                INSERT INTO risk_rule_states (
                    database_config_id, rule_id, warning_candidate_since,
                    critical_candidate_since, fatal_candidate_since, recovery_since,
                    last_observed_at, last_metric_id, updated_at
                ) VALUES (?, 'CONNECTION_RATIO', ?, ?, ?, ?, ?, ?, ?)
                """,
                TARGET_ID,
                Timestamp.from(at(0)),
                Timestamp.from(at(1)),
                Timestamp.from(at(2)),
                Timestamp.from(at(3)),
                Timestamp.from(at(5)),
                metricId,
                Timestamp.from(at(5)));
        insertOpenConnectionIncident(at(5));
        long deliveryId = insertPendingDelivery();
        String stateBefore = rowJson("monitoring_states", "database_config_id", TARGET_ID);
        String incidentBefore = rowJson("incidents", "incident_id", INCIDENT_ID);
        String deliveryBefore = rowJson("notification_deliveries", "id", deliveryId);

        startup.verifyPrerequisite();

        assertThat(jdbc.queryForMap("""
                SELECT warning_candidate_since, critical_candidate_since,
                       fatal_candidate_since, recovery_since,
                       last_observed_at, last_metric_id, updated_at
                FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, TARGET_ID))
                .containsEntry("warning_candidate_since", null)
                .containsEntry("critical_candidate_since", null)
                .containsEntry("fatal_candidate_since", null)
                .containsEntry("recovery_since", null)
                .containsEntry("last_metric_id", metricId);
        assertThat(jdbc.queryForObject("""
                SELECT last_observed_at FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(5));
        assertThat(jdbc.queryForObject("""
                SELECT updated_at FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(5));
        assertThat(rowJson("monitoring_states", "database_config_id", TARGET_ID))
                .isEqualTo(stateBefore);
        assertThat(rowJson("incidents", "incident_id", INCIDENT_ID))
                .isEqualTo(incidentBefore);
        assertThat(rowJson("notification_deliveries", "id", deliveryId))
                .isEqualTo(deliveryBefore);

        jdbc.update("""
                UPDATE risk_rule_states SET warning_candidate_since=?
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Timestamp.from(at(4)), TARGET_ID);
        startup.verifyPrerequisite();
        assertThat(jdbc.queryForObject("""
                SELECT warning_candidate_since FROM risk_rule_states
                WHERE database_config_id=? AND rule_id='CONNECTION_RATIO'
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(4));
    }

    @Test
    void durableCollectionDuringDeliveryOutageDefersStaleWithoutEvaluatingMetric() {
        insertMetric(25);

        assertThat(store.findStaleCandidates(at(30), 100)).isEmpty();
        assertThat(staleTransaction.process(new StaleCandidate(TARGET_ID, at(30)), at(30)))
                .isEqualTo(RiskStaleTransaction.Outcome.IGNORED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();

        List<StaleCandidate> due = store.findStaleCandidates(at(55), 100);
        assertThat(due).containsExactly(new StaleCandidate(TARGET_ID, at(55)));
        assertThat(staleTransaction.process(due.get(0), at(55)))
                .isEqualTo(RiskStaleTransaction.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT opened_at FROM incidents", Timestamp.class).toInstant())
                .isEqualTo(at(55));
    }

    @Test
    void firstDueScanUsesLogicalDueAtAndRunsAtExactBoundary() {
        startup.verifyPrerequisite();

        assertThat(store.findStaleCandidates(at(29), 100)).isEmpty();
        List<StaleCandidate> due = store.findStaleCandidates(at(30), 100);
        assertThat(due).containsExactly(new StaleCandidate(TARGET_ID, at(30)));

        assertThat(staleTransaction.process(due.get(0), at(30).plusMillis(700)))
                .isEqualTo(RiskStaleTransaction.Outcome.APPLIED);

        assertThat(jdbc.queryForObject("""
                SELECT data_freshness FROM monitoring_states WHERE database_config_id=?
                """, String.class, TARGET_ID)).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("""
                SELECT updated_at FROM monitoring_states WHERE database_config_id=?
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(30));
        assertThat(jdbc.queryForObject("""
                SELECT opened_at FROM incidents
                WHERE database_config_id=? AND rule_id='COLLECTION_STALE' AND status='OPEN'
                """, Timestamp.class, TARGET_ID).toInstant()).isEqualTo(at(30));
        assertThat(jdbc.queryForList(
                "SELECT event_type FROM event_outbox ORDER BY seq", String.class))
                .containsExactly("IncidentCreatedEvent", "MonitoringStatusChangedEvent");
    }

    @Test
    void repeatDueChangesFreshStateWithoutReopeningExistingStaleIncident() {
        long metricId = insertMetric(25);
        jdbc.update("""
                UPDATE monitoring_states
                SET state_version=2, connection_status='DOWN', data_freshness='FRESH',
                    last_attempt_at=?, latest_metric_id=?, updated_at=?
                WHERE database_config_id=?
                """, Timestamp.from(at(25)), metricId, Timestamp.from(at(31)), TARGET_ID);
        insertOpenStaleIncident(at(30));

        StaleCandidate due = store.findStaleCandidates(at(55), 100).get(0);
        assertThat(staleTransaction.process(due, at(55).plusMillis(400)))
                .isEqualTo(RiskStaleTransaction.Outcome.APPLIED);

        assertThat(jdbc.queryForObject(
                "SELECT data_freshness FROM monitoring_states WHERE database_config_id=?",
                String.class,
                TARGET_ID)).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isOne();
        assertThat(jdbc.queryForList(
                "SELECT event_type FROM event_outbox ORDER BY seq", String.class))
                .containsExactly("MonitoringStatusChangedEvent");
        assertThat(jdbc.queryForObject(
                "SELECT opened_at FROM incidents WHERE incident_id=?",
                Timestamp.class,
                INCIDENT_ID).toInstant()).isEqualTo(at(30));
    }

    @Test
    void dueCandidateIsIgnoredWhenAConcurrentMetricMovesTheAuthoritativeDeadline() {
        StaleCandidate obsolete = store.findStaleCandidates(at(30), 100).get(0);
        long metricId = insertMetric(5);
        jdbc.update("""
                UPDATE monitoring_states
                SET state_version=2, data_freshness='FRESH', connection_status='UP',
                    last_attempt_at=?, last_success_at=?, latest_metric_id=?, updated_at=?
                WHERE database_config_id=?
                """,
                Timestamp.from(at(5)),
                Timestamp.from(at(5)),
                metricId,
                Timestamp.from(at(5)),
                TARGET_ID);

        assertThat(staleTransaction.process(obsolete, at(30).plusMillis(500)))
                .isEqualTo(RiskStaleTransaction.Outcome.IGNORED);
        assertThat(jdbc.queryForObject(
                "SELECT data_freshness FROM monitoring_states WHERE database_config_id=?",
                String.class,
                TARGET_ID)).isEqualTo("FRESH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();
    }

    @Test
    void durableBasisRejectsOtherVersionsPreactivationAndFutureRows() {
        insertMetric(-1);
        long oldVersion = insertMetric(29);
        jdbc.update("UPDATE metric_data SET config_version=2 WHERE id=?", oldVersion);
        insertMetric(100);
        assertThat(store.findStaleCandidates(at(30), 100))
                .containsExactly(new StaleCandidate(TARGET_ID, at(30)));
        insertMetric(25);
        assertThat(store.findStaleCandidates(at(30), 100)).isEmpty();
        assertThat(staleTransaction.process(new StaleCandidate(TARGET_ID, at(30)), at(30)))
                .isEqualTo(RiskStaleTransaction.Outcome.IGNORED);
        assertThat(store.findStaleCandidates(at(55), 100))
                .containsExactly(new StaleCandidate(TARGET_ID, at(55)));
    }

    @Test
    void failedDurableAttemptStillDefersCollectionStaleWithoutInventingRecovery() {
        long metricId = insertMetric(25);
        jdbc.update("UPDATE metric_data SET collection_status='CONNECTION_FAILED',last_success_at=NULL WHERE id=?", metricId);
        insertOpenConnectionIncident(at(5));
        String stateBefore = rowJson("monitoring_states", "database_config_id", TARGET_ID);
        String incidentBefore = rowJson("incidents", "incident_id", INCIDENT_ID);
        assertThat(staleTransaction.process(new StaleCandidate(TARGET_ID, at(30)), at(30)))
                .isEqualTo(RiskStaleTransaction.Outcome.IGNORED);
        assertThat(rowJson("monitoring_states", "database_config_id", TARGET_ID)).isEqualTo(stateBefore);
        assertThat(rowJson("incidents", "incident_id", INCIDENT_ID)).isEqualTo(incidentBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_rule_states", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();
        StaleCandidate due = store.findStaleCandidates(at(55), 100).get(0);
        assertThat(staleTransaction.process(due, at(55))).isEqualTo(RiskStaleTransaction.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT risk_level FROM monitoring_states WHERE database_config_id=?", String.class, TARGET_ID)).isEqualTo("FATAL");
    }

    @Test
    void durablyFreshTargetsCannotHideDueTargetBeyondCandidateBatch() throws Exception {
        insertMetric(25);
        for (long id = 13; id <= 16; id++) {
            seedTargetStateAndPolicy(id);
            if (id < 16) {
                insertMetric(id, 25);
            }
        }
        assertThat(store.findStaleCandidates(at(30), 2))
                .containsExactly(new StaleCandidate(16, at(30)));
    }

    @Test
    void durableDeadlineTimerRollsBackStateIncidentAndClocksWhenOutboxFails() {
        insertMetric(25);
        String before = rowJson("monitoring_states", "database_config_id", TARGET_ID);
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT t16_test_reject_status CHECK (event_type <> 'MonitoringStatusChangedEvent')");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    staleTransaction.process(new StaleCandidate(TARGET_ID, at(55)), at(56)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rowJson("monitoring_states", "database_config_id", TARGET_ID)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_rule_states", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT t16_test_reject_status");
        }
    }

    @Test
    void waitsForConcurrentCollectorTargetLockThenReadsItsCommittedMetric() throws Exception {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var collector = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                store.lockTarget(TARGET_ID).orElseThrow();
                insertMetric(25);
                locked.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("collector lock was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var timer = executor.submit(() -> staleTransaction.process(new StaleCandidate(TARGET_ID, at(30)), at(30)));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            long waiting;
            do {
                waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FROM database_configs%'", Long.class);
                if (waiting == 0) {
                    Thread.sleep(20);
                }
            } while (waiting == 0 && System.nanoTime() < deadline);
            assertThat(waiting).isPositive();
            assertThat(timer.isDone()).isFalse();
            release.countDown();
            collector.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(timer.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(RiskStaleTransaction.Outcome.IGNORED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
    private void seedTargetStateAndPolicy() throws Exception {
        seedTargetStateAndPolicy(TARGET_ID);
    }

    private void seedTargetStateAndPolicy(long targetId) throws Exception {
        jdbc.update("""
                INSERT INTO database_configs
                    (id, collection_interval_seconds, created_at, enabled, host, name, port, status,
                     config_version, deleted_at)
                VALUES (?, 5, ?, true, '127.0.0.1', 'production', 13306, 'UNKNOWN', 1, null)
                """, targetId, Timestamp.from(ACTIVATED_AT));
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, risk_level, activation_at, updated_at)
                VALUES (?, 1, 1, true, false, 'UNKNOWN', 'NO_DATA', null, ?, ?)
                """, targetId, Timestamp.from(ACTIVATED_AT), Timestamp.from(ACTIVATED_AT));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, CAST(? AS jsonb), 30, 300, ?, ?)
                """, targetId, mapper.writeValueAsString(MonitoringContracts.defaultRules()),
                Timestamp.from(ACTIVATED_AT), Timestamp.from(ACTIVATED_AT));
    }

    private long insertMetric(int second) {
        return insertMetric(TARGET_ID, second);
    }

    private long insertMetric(long targetId, int second) {
        return jdbc.queryForObject("""
                INSERT INTO metric_data (
                    collection_status, created_at, database_config_id, timestamp,
                    config_version, collection_attempt_time, last_success_at,
                    unavailable_metrics
                ) VALUES ('SUCCESS', ?, ?, ?, 1, ?, ?, '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                Timestamp.from(at(second)),
                targetId,
                Timestamp.from(at(second)),
                Timestamp.from(at(second)),
                Timestamp.from(at(second)));
    }

    private void insertOpenConnectionIncident(Instant openedAt) {
        insertIncident("CONNECTION_FAILURE", "CONNECTION_FAILURE", "FATAL", openedAt,
                "connectionStatus", null, null, "Connection failed");
    }

    private void insertOpenStaleIncident(Instant openedAt) {
        insertIncident("COLLECTION_STALE", "COLLECTION_STALE", "CRITICAL", openedAt,
                "collectionAgeSeconds", 30, 30, "Collection is stale");
    }

    private void insertIncident(
            String ruleId,
            String ruleType,
            String severity,
            Instant openedAt,
            String metricName,
            Integer metricValue,
            Integer thresholdValue,
            String message
    ) {
        jdbc.update("""
                INSERT INTO incidents (
                    incident_id, database_config_id, database_name, rule_id, rule_type,
                    severity, status, opened_at, last_observed_at, metric_name,
                    metric_value, threshold_value, message, incident_version
                ) VALUES (?, ?, 'production', ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, 1)
                """,
                INCIDENT_ID,
                TARGET_ID,
                ruleId,
                ruleType,
                severity,
                Timestamp.from(openedAt),
                Timestamp.from(openedAt),
                metricName,
                metricValue,
                thresholdValue,
                message);
    }

    private long insertPendingDelivery() {
        long webhookId = jdbc.queryForObject("""
                INSERT INTO notification_webhooks (
                    name, provider, url_key_version, url_nonce, url_ciphertext,
                    enabled, created_at, updated_at
                ) VALUES ('ops', 'SLACK', 1, decode('000000000000000000000000','hex'),
                          decode('0000000000000000000000000000000000','hex'), true, ?, ?)
                RETURNING id
                """, Long.class, Timestamp.from(at(5)), Timestamp.from(at(5)));
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries (
                    incident_id, incident_version, notification_type, channel,
                    notification_webhook_id, status, attempt_count, next_attempt_at,
                    expires_at, created_at
                ) VALUES (?, 1, 'INCIDENT_OPENED', 'SLACK', ?, 'PENDING', 0, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                INCIDENT_ID,
                webhookId,
                Timestamp.from(at(10)),
                Timestamp.from(at(610)),
                Timestamp.from(at(5)));
    }

    private String rowJson(String table, String key, Object value) {
        return jdbc.queryForObject(
                "SELECT to_jsonb(t)::text FROM " + table + " t WHERE " + key + "=?",
                String.class,
                value);
    }

    private void cleanup() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM notification_webhooks");
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
}
