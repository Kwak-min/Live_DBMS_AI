package com.example.monitoring.lifecycle.integration;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.example.monitoring.lifecycle.port.TargetChange;
import com.example.monitoring.lifecycle.port.TargetChangeType;
import com.example.monitoring.repository.DatabaseConfigRepository;
import com.example.monitoring.service.AuditEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.collector.enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.outbox.retention-cleanup-enabled=false",
        "app.legacy.blocked-reasons-cleanup-enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.database-security.verify-on-startup=false",
        "monitoring.realtime.enabled=false"
})
@ActiveProfiles("local")
class MonitoringLifecyclePostgresIntegrationTest {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long ACTOR_ID = 701L;
    private static final String CLIENT_IP = "127.0.0.1";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_KEYS = "{\"1\":\"" + KEY + "\"}";
    private static final String PREVIOUS_ACTIVE_KEY_VERSION = System.getProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
    private static final String PREVIOUS_ENCRYPTION_KEYS = System.getProperty("DB_CONFIG_ENCRYPTION_KEYS");
    private static final String PREVIOUS_LEGACY_TIME_ZONE = System.getProperty("LEGACY_TIME_ZONE");
    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
            System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", DB_KEYS);
            System.setProperty("LEGACY_TIME_ZONE", "Asia/Seoul");
            POSTGRES = EmbeddedPostgres.start();
        } catch (Exception exception) {
            restoreProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
            restoreProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
            restoreProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.auth.jwt-signing-keys", () -> "{\"test\":\"" + KEY + "\"}");
        registry.add("app.auth.jwt-active-kid", () -> "test");
        registry.add("app.database-security.encryption-keys", () -> DB_KEYS);
        registry.add("app.database-security.active-key-version", () -> "1");
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
        restoreProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
        restoreProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
        restoreProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseConfigRepository databaseConfigs;

    @Autowired
    private AuditEventService auditEvents;

    @Autowired
    private MonitoringLifecyclePort lifecycle;

    private TransactionTemplate transactions;

    @BeforeEach
    void resetActiveV5() {
        transactions = new TransactionTemplate(transactionManager);
        cleanupTestFixtures();
        installOutboxLedger();
    }

    @AfterEach
    void removeTestFixtures() {
        cleanupTestFixtures();
    }

    @Test
    void applicationStartsWithActiveV5AndRequiredLifecycleSchema() {
        assertThat(applicationContext.getBean(MonitoringLifecyclePort.class)).isSameAs(lifecycle);
        assertThat(Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().getVersion()))
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(tableExists("monitoring_states")).isTrue();
        assertThat(tableExists("event_outbox")).isTrue();
        assertThat(tableExists("processed_events")).isTrue();
        assertThat(hasConstraint("auth_sessions", "auth_sessions_sid_user_unique")).isTrue();
        assertThat(hasConstraint("metric_data", "metric_data_id_target_unique")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM database_configs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
        proof("active-v5", "migrations=1,2,3,4,5", "targetRows=0", "auditRows=0");
    }

    @Test
    void actualV3PrerequisitesAndCreatedTargetsHaveExactDefaultsAndPayloads() throws Exception {
        assertActualPrerequisites();

        long enabledId = createWithLifecycle("enabled-target", true, at(0));
        long disabledId = createWithLifecycle("disabled-target", false, at(1));

        assertState(enabledId, 1L, 1L, true, false, "NO_DATA", normalized(at(0)), normalized(at(0)));
        assertState(disabledId, 1L, 1L, false, false, "PAUSED", null, normalized(at(1)));
        assertDefaultPolicy(enabledId);
        assertDefaultPolicy(disabledId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isEqualTo(2L);

        List<OutboxRow> rows = outboxRows();
        assertThat(rows).hasSize(2);
        assertStatusPayload(rows.get(0).payload(), enabledId, 1L, 1L, true, false,
                "NO_DATA", normalized(at(0)));
        assertStatusPayload(rows.get(1).payload(), disabledId, 1L, 1L, false, false,
                "PAUSED", normalized(at(1)));
        assertOutboxEnvelope(rows.get(0), enabledId, normalized(at(0)));
        assertOutboxEnvelope(rows.get(1), disabledId, normalized(at(1)));
        proof("created-defaults", "targets=2", "states=2", "policies=2", "outbox=2");
    }

    @Test
    void fiveActionsPreservePolicyResetStateCloseFourRulesCancelPendingAndOrderExactEvents() throws Exception {
        long targetId = createWithLifecycle("original-name", true, at(0));
        JsonNode customizedRules = objectMapper.readTree("""
                [
                  {"ruleId":"CONNECTION_RATIO","metricName":"activeConnectionsRatio","operator":"GTE",
                   "warningThreshold":0.75,"criticalThreshold":0.85,"fatalThreshold":0.98,
                   "sustainSeconds":30,"recoverySeconds":20,"enabled":true},
                  {"ruleId":"SLOW_QUERY_RATE","metricName":"slowQueriesPerSecond","operator":"GTE",
                   "warningThreshold":2.0,"criticalThreshold":7.0,"fatalThreshold":null,
                   "sustainSeconds":25,"recoverySeconds":35,"enabled":true}
                ]
                """);
        jdbc.update("""
                UPDATE risk_policies
                SET version = 7, rules = CAST(? AS jsonb), stale_after_seconds = 120,
                    notification_cooldown_seconds = 600, updated_at = ?
                WHERE database_config_id = ?
                """, customizedRules.toString(), Timestamp.from(normalized(at(0).plusSeconds(1))), targetId);
        String customizedPolicy = rows("risk_policies", "database_config_id");

        long metricId = primeObservedState(targetId, at(1));
        List<IncidentSeed> updatedIncidents = fourRuleIncidents(0, targetId, "original-name", metricId, at(2));
        seedIncidentsAndPendingDeliveries(updatedIncidents);
        applyExisting(targetId, TargetChangeType.UPDATED, "renamed-target", true, at(2));

        assertState(targetId, 2L, 2L, true, false, "NO_DATA", normalized(at(2)), normalized(at(2)));
        assertResolved(updatedIncidents, "CONFIG_CHANGED", at(2));
        assertAllPendingCancelled(updatedIncidents.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_rule_states", Long.class)).isZero();
        assertThat(rows("risk_policies", "database_config_id")).isEqualTo(customizedPolicy);

        primeObservedState(targetId, at(3));
        List<IncidentSeed> pausedIncidents = List.of(incident(100, targetId, "renamed-target",
                "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED", metricId, 5L, at(4), "pause evidence"));
        seedIncidentsAndPendingDeliveries(pausedIncidents);
        applyExisting(targetId, TargetChangeType.PAUSED, "renamed-target", false, at(4));
        assertState(targetId, 3L, 3L, false, false, "PAUSED", null, normalized(at(4)));
        assertResolved(pausedIncidents, "MONITORING_PAUSED", at(4));

        List<IncidentSeed> resumedIncidents = List.of(incident(200, targetId, "renamed-target",
                "COLLECTION_STALE", "COLLECTION_STALE", metricId, 6L, at(5), "resume evidence"));
        seedIncidentsAndPendingDeliveries(resumedIncidents);
        applyExisting(targetId, TargetChangeType.RESUMED, "renamed-target", true, at(5));
        assertState(targetId, 4L, 4L, true, false, "NO_DATA", normalized(at(5)), normalized(at(5)));
        assertResolved(resumedIncidents, "CONFIG_CHANGED", at(5));

        primeObservedState(targetId, at(6));
        List<IncidentSeed> deletedIncidents = List.of(incident(300, targetId, "renamed-target",
                "CONNECTION_FAILURE", "CONNECTION_FAILURE", metricId, 7L, at(7), "delete evidence"));
        seedIncidentsAndPendingDeliveries(deletedIncidents);
        applyExisting(targetId, TargetChangeType.DELETED, "renamed-target", false, at(7));
        assertState(targetId, 5L, 5L, false, true, "PAUSED", null, normalized(at(7)));
        assertResolved(deletedIncidents, "TARGET_DELETED", at(7));

        assertThat(rows("risk_policies", "database_config_id")).isEqualTo(customizedPolicy);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isEqualTo(7L);
        assertAllPendingCancelled(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isEqualTo(12L);
        outboxRows().forEach(row -> assertOutboxEnvelope(
                row,
                targetId,
                Instant.parse(row.payload().path(row.eventType().equals("IncidentResolvedEvent")
                        ? "resolvedAt" : "updatedAt").asText())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isEqualTo(5L);
        assertDeletedTarget(targetId);
        assertLedgerOrder(updatedIncidents, pausedIncidents, resumedIncidents, deletedIncidents);
        assertExactResolutionPayload(updatedIncidents.get(0), "CONFIG_CHANGED", at(2));
        assertLatestStatusPayload(targetId, 5L, 5L, false, true, "PAUSED", at(7));
        proof("five-actions", "configVersions=1..5", "stateVersions=1..5", "resolved=7",
                "deliveriesCancelled=7", "outbox=12", "policyVersion=7");
    }

    @Test
    void invalidVersionTypeTargetAndPostDeleteChangesLeaveEveryStoreUnchanged() {
        long targetId = createWithLifecycle("guard-target", true, at(0));

        assertRejectedWithoutRowChanges(() -> transactions.executeWithoutResult(ignored -> {
            audit(databaseConfigs.findById(targetId).orElseThrow(), AuditAction.DATABASE_CREATED, 1L);
            lifecycle.applyChange(new TargetChange(targetId, 1L, TargetChangeType.CREATED, true,
                    "guard-target", at(1), ACTOR_ID, requestId(1L)));
        }), "no monitoring state");

        assertRejectedMutation(targetId, 1L, true, "same-version", false,
                TargetChangeType.UPDATED, 1L, true, "same-version", "advance by exactly one");
        assertRejectedMutation(targetId, 0L, true, "lower-version", false,
                TargetChangeType.UPDATED, 0L, true, "lower-version", "positive JavaScript-safe integer");
        assertRejectedMutation(targetId, 3L, true, "gap-version", false,
                TargetChangeType.UPDATED, 3L, true, "gap-version", "advance by exactly one");
        assertRejectedMutation(targetId, 2L, true, "pause-mismatch", false,
                TargetChangeType.PAUSED, 2L, true, "pause-mismatch", "PAUSED requires enabled=false");
        assertRejectedMutation(targetId, 2L, true, "actual-name", false,
                TargetChangeType.UPDATED, 2L, true, "wire-name", "does not match the flushed B row");

        long missingStateId = createBOnly("missing-state", true, 1L);
        assertRejectedMutation(missingStateId, 2L, true, "missing-state-v2", false,
                TargetChangeType.UPDATED, 2L, true, "missing-state-v2", "requires an existing monitoring state");

        applyExisting(targetId, TargetChangeType.PAUSED, "guard-target", false, at(2));
        applyExisting(targetId, TargetChangeType.RESUMED, "guard-target", true, at(3));
        applyExisting(targetId, TargetChangeType.DELETED, "guard-target", false, at(4));
        assertRejectedWithoutRowChanges(() -> transactions.executeWithoutResult(ignored -> {
            DatabaseConfig deleted = databaseConfigs.findById(targetId).orElseThrow();
            audit(deleted, AuditAction.DATABASE_DELETED, 5L);
            lifecycle.applyChange(new TargetChange(targetId, 4L, TargetChangeType.DELETED, false,
                    "guard-target", at(5), ACTOR_ID, requestId(5L)));
        }), "already deleted");
        proof("invalid-guards", "duplicate=true", "sameLowerGap=true", "typeMismatch=true",
                "targetMismatch=true", "missingState=true", "postDelete=true");
    }

    @Test
    void unsafeIdentifiersStateIncidentAndPayloadOverflowLeaveEveryStoreUnchanged() {
        long stateOverflowId = createWithLifecycle("state-overflow", true, at(0));
        jdbc.update("UPDATE monitoring_states SET state_version = ? WHERE database_config_id = ?",
                MAX_SAFE_INTEGER, stateOverflowId);
        assertRejectedMutation(stateOverflowId, 2L, true, "state-overflow-v2", false,
                TargetChangeType.UPDATED, 2L, true, "state-overflow-v2", "stateVersion cannot be incremented safely");

        long incidentOverflowId = createWithLifecycle("incident-overflow", true, at(1));
        long incidentMetric = primeObservedState(incidentOverflowId, at(2));
        List<IncidentSeed> overflowIncident = List.of(incident(400, incidentOverflowId, "incident-overflow",
                "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED", incidentMetric,
                MAX_SAFE_INTEGER, at(3), "incident overflow"));
        seedIncidentsAndPendingDeliveries(overflowIncident);
        assertRejectedMutation(incidentOverflowId, 2L, true, "incident-overflow-v2", false,
                TargetChangeType.UPDATED, 2L, true, "incident-overflow-v2",
                "incidentVersion cannot be incremented safely");

        long payloadOverflowId = createWithLifecycle("payload-overflow", true, at(4));
        long payloadMetric = primeObservedState(payloadOverflowId, at(5));
        List<IncidentSeed> oversized = List.of(incident(500, payloadOverflowId, "payload-overflow",
                "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH", payloadMetric, 1L, at(6), "가".repeat(22_000)));
        seedIncidentsAndPendingDeliveries(oversized);
        assertRejectedMutation(payloadOverflowId, 2L, true, "payload-overflow-v2", false,
                TargetChangeType.UPDATED, 2L, true, "payload-overflow-v2", "exceeds 64KiB");

        assertRejectedWithoutRowChanges(() -> transactions.executeWithoutResult(ignored -> lifecycle.applyChange(
                new TargetChange(MAX_SAFE_INTEGER + 1L, 1L, TargetChangeType.CREATED, true,
                        "unsafe-id", at(7), ACTOR_ID, requestId(7L)))), "databaseConfigId");

        long configOverflowId = createWithLifecycle("config-overflow", true, at(8));
        assertRejectedMutation(configOverflowId, MAX_SAFE_INTEGER + 1L, true, "config-overflow-v2", false,
                TargetChangeType.UPDATED, MAX_SAFE_INTEGER + 1L, true, "config-overflow-v2", "configVersion");
        proof("overflow-guards", "state=true", "incident=true", "payloadBytes>65536",
                "databaseConfigId=true", "configVersion=true");
    }

    @Test
    void forcedOutboxFailureRollsBackBJpaAuditLifecycleAndOutboxEvenWhenCaught() {
        installForcedOutboxFailureTrigger();

        AtomicReference<Throwable> lifecycleFailure = new AtomicReference<>();
        Throwable commitFailure = catchThrowable(() -> transactions.executeWithoutResult(ignored -> {
            DatabaseConfig saved = databaseConfigs.saveAndFlush(target("forced-outbox", true, 1L));
            audit(saved, AuditAction.DATABASE_CREATED, 1L);
            try {
                lifecycle.applyChange(change(saved, TargetChangeType.CREATED, at(0), 1L));
            } catch (RuntimeException exception) {
                lifecycleFailure.set(exception);
            }
        }));

        assertThat(lifecycleFailure.get()).isNotNull();
        assertThat(rootMessage(lifecycleFailure.get())).contains("forced lifecycle outbox failure");
        assertThat(commitFailure).isInstanceOf(UnexpectedRollbackException.class);
        assertAllStoreCountsZero();
        proof("forced-outbox-create", "caughtInsideOuterTransaction=true", "unexpectedRollback=true",
                "bTargets=0", "bAudit=0", "cRows=0", "outbox=0");

        jdbc.execute("DROP TRIGGER lifecycle_force_outbox_failure_trigger ON event_outbox");
        long targetId = createWithLifecycle("forced-update", true, at(1));
        long metricId = primeObservedState(targetId, at(2));
        List<IncidentSeed> incidents = fourRuleIncidents(
                600, targetId, "forced-update", metricId, at(3));
        seedIncidentsAndPendingDeliveries(incidents);
        DatabaseSnapshot before = snapshot();
        installForcedOutboxFailureTrigger();

        AtomicReference<Throwable> updateLifecycleFailure = new AtomicReference<>();
        Throwable updateCommitFailure = catchThrowable(() -> transactions.executeWithoutResult(ignored -> {
            DatabaseConfig target = databaseConfigs.findActiveByIdForUpdate(targetId).orElseThrow();
            target.setName("forced-update-v2");
            target.setConfigVersion(2L);
            target.setStatus(TargetDbStatus.UNKNOWN);
            target.setLastCheckedAt(null);
            target.setLastSuccessAt(null);
            target.setLastErrorMessage(null);
            DatabaseConfig saved = databaseConfigs.saveAndFlush(target);
            audit(saved, AuditAction.DATABASE_UPDATED, 2L);
            try {
                lifecycle.applyChange(change(saved, TargetChangeType.UPDATED, at(3), 2L));
            } catch (RuntimeException exception) {
                updateLifecycleFailure.set(exception);
            }
        }));

        assertThat(updateLifecycleFailure.get()).isNotNull();
        assertThat(rootMessage(updateLifecycleFailure.get())).contains("forced lifecycle outbox failure");
        assertThat(updateCommitFailure).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE status = 'OPEN'", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries WHERE status = 'PENDING'", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isEqualTo(1L);
        proof("forced-outbox-update", "caughtInsideOuterTransaction=true", "unexpectedRollback=true",
                "snapshotUnchanged=true", "openIncidents=4", "pendingDeliveries=4", "auditRows=1", "outboxRows=1");
    }

    private void installOutboxLedger() {
        jdbc.execute("""
                CREATE TABLE lifecycle_outbox_insert_ledger (
                    sequence BIGSERIAL PRIMARY KEY,
                    event_id UUID NOT NULL,
                    event_type VARCHAR(100) NOT NULL,
                    payload JSONB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE FUNCTION lifecycle_capture_outbox_insert()
                RETURNS trigger LANGUAGE plpgsql AS $function$
                BEGIN
                    INSERT INTO lifecycle_outbox_insert_ledger(event_id, event_type, payload)
                    VALUES (NEW.event_id, NEW.event_type, NEW.payload);
                    RETURN NEW;
                END
                $function$
                """);
        jdbc.execute("""
                CREATE TRIGGER lifecycle_capture_outbox_insert_trigger
                AFTER INSERT ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION lifecycle_capture_outbox_insert()
                """);
    }

    private void cleanupTestFixtures() {
        jdbc.execute("DROP TABLE IF EXISTS lifecycle_outbox_insert_ledger CASCADE");
        jdbc.execute("DROP FUNCTION IF EXISTS lifecycle_capture_outbox_insert() CASCADE");
        jdbc.execute("DROP FUNCTION IF EXISTS lifecycle_force_outbox_failure() CASCADE");
        for (String table : List.of(
                "notification_deliveries", "push_subscriptions", "notification_webhooks",
                "risk_rule_states", "incidents", "risk_policies", "monitoring_states",
                "event_outbox", "processed_events", "audit_logs", "metric_data",
                "database_configs")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private void installForcedOutboxFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION lifecycle_force_outbox_failure()
                RETURNS trigger LANGUAGE plpgsql AS $function$
                BEGIN
                    RAISE EXCEPTION 'forced lifecycle outbox failure';
                END
                $function$
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS lifecycle_force_outbox_failure_trigger ON event_outbox");
        jdbc.execute("""
                CREATE TRIGGER lifecycle_force_outbox_failure_trigger
                BEFORE INSERT ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION lifecycle_force_outbox_failure()
                """);
    }

    private void assertActualPrerequisites() {
        assertThat(hasConstraint("auth_sessions", "auth_sessions_sid_user_unique")).isTrue();
        assertThat(hasConstraint("metric_data", "metric_data_id_target_unique")).isTrue();
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, udt_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'event_outbox'
                ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(row -> row.get("column_name")).containsExactly(
                "event_id", "seq", "event_type", "stream_key", "ordering_key", "payload",
                "created_at", "published_at", "attempts", "next_attempt_at", "last_error");
        assertThat(columns.get(5).get("udt_name")).isEqualTo("jsonb");
        assertThat(columns.get(6).get("udt_name")).isEqualTo("timestamptz");
        assertThat(columns.get(7).get("udt_name")).isEqualTo("timestamptz");
        assertThat(columns.get(9).get("udt_name")).isEqualTo("timestamptz");
    }

    private long createWithLifecycle(String name, boolean enabled, Instant occurredAt) {
        Long id = transactions.execute(ignored -> {
            DatabaseConfig saved = databaseConfigs.saveAndFlush(target(name, enabled, 1L));
            audit(saved, AuditAction.DATABASE_CREATED, 1L);
            lifecycle.applyChange(change(saved, TargetChangeType.CREATED, occurredAt, 1L));
            return saved.getId();
        });
        return java.util.Objects.requireNonNull(id);
    }

    private long createBOnly(String name, boolean enabled, long version) {
        Long id = transactions.execute(ignored -> {
            DatabaseConfig saved = databaseConfigs.saveAndFlush(target(name, enabled, version));
            audit(saved, AuditAction.DATABASE_CREATED, version);
            return saved.getId();
        });
        return java.util.Objects.requireNonNull(id);
    }

    private void applyExisting(long id, TargetChangeType type, String name, boolean enabled, Instant occurredAt) {
        transactions.executeWithoutResult(ignored -> {
            DatabaseConfig target = databaseConfigs.findActiveByIdForUpdate(id).orElseThrow();
            long version = target.getConfigVersion() + 1L;
            target.setName(name);
            target.setEnabled(enabled);
            target.setConfigVersion(version);
            target.setStatus(TargetDbStatus.UNKNOWN);
            target.setLastCheckedAt(null);
            target.setLastSuccessAt(null);
            target.setLastErrorMessage(null);
            if (type == TargetChangeType.DELETED) {
                target.setDeletedAt(LocalDateTime.ofInstant(normalized(occurredAt), ZoneOffset.UTC));
            }
            DatabaseConfig saved = databaseConfigs.saveAndFlush(target);
            audit(saved, type == TargetChangeType.DELETED
                    ? AuditAction.DATABASE_DELETED : AuditAction.DATABASE_UPDATED, version);
            lifecycle.applyChange(change(saved, type, occurredAt, version));
        });
    }

    private void assertRejectedMutation(
            long id,
            long storedVersion,
            boolean storedEnabled,
            String storedName,
            boolean storedDeleted,
            TargetChangeType type,
            long changeVersion,
            boolean changeEnabled,
            String changeName,
            String message
    ) {
        assertRejectedWithoutRowChanges(() -> transactions.executeWithoutResult(ignored -> {
            DatabaseConfig target = databaseConfigs.findActiveByIdForUpdate(id).orElseThrow();
            target.setConfigVersion(storedVersion);
            target.setEnabled(storedEnabled);
            target.setName(storedName);
            if (storedDeleted) {
                target.setDeletedAt(LocalDateTime.ofInstant(normalized(at(9)), ZoneOffset.UTC));
            }
            DatabaseConfig saved = databaseConfigs.saveAndFlush(target);
            audit(saved, type == TargetChangeType.DELETED
                    ? AuditAction.DATABASE_DELETED : AuditAction.DATABASE_UPDATED, storedVersion);
            lifecycle.applyChange(new TargetChange(id, changeVersion, type, changeEnabled, changeName,
                    at(9), ACTOR_ID, requestId(changeVersion)));
        }), message);
    }

    private void assertRejectedWithoutRowChanges(Runnable attempt, String message) {
        DatabaseSnapshot before = snapshot();
        assertThatThrownBy(attempt::run).hasMessageContaining(message);
        assertThat(snapshot()).isEqualTo(before);
    }

    private void audit(DatabaseConfig target, AuditAction action, long version) {
        auditEvents.success(ACTOR_ID, action, AuditTargetType.DATABASE,
                target.getId().toString(), target.getId(), CLIENT_IP, requestId(version),
                "Lifecycle integration " + action.name());
    }

    private TargetChange change(DatabaseConfig target, TargetChangeType type, Instant occurredAt, long version) {
        return new TargetChange(target.getId(), version, type, target.getEnabled(), target.getName(),
                occurredAt, ACTOR_ID, requestId(version));
    }

    private DatabaseConfig target(String name, boolean enabled, long version) {
        return DatabaseConfig.builder()
                .name(name)
                .host("127.0.0.1")
                .port(3306)
                .databaseName("lifecycle")
                .status(TargetDbStatus.UNKNOWN)
                .collectionIntervalSeconds(5)
                .enabled(enabled)
                .configVersion(version)
                .build();
    }

    private long primeObservedState(long targetId, Instant observedAt) {
        Long metricId = jdbc.queryForObject("""
                INSERT INTO metric_data (
                    collection_status, created_at, database_config_id, timestamp,
                    config_version, collection_attempt_time, last_success_at
                )
                SELECT 'SUCCESS', ?, ?, ?, config_version, ?, ?
                FROM monitoring_states
                WHERE database_config_id = ?
                RETURNING id
                """, Long.class,
                Timestamp.from(normalized(observedAt)), targetId, Timestamp.from(normalized(observedAt)),
                Timestamp.from(normalized(observedAt)), Timestamp.from(normalized(observedAt)), targetId);
        jdbc.update("""
                UPDATE monitoring_states
                SET connection_status = 'UP', data_freshness = 'FRESH', risk_level = 'FATAL',
                    last_attempt_at = ?, last_success_at = ?, latest_metric_id = ?, updated_at = ?
                WHERE database_config_id = ?
                """, Timestamp.from(normalized(observedAt)), Timestamp.from(normalized(observedAt)), metricId,
                Timestamp.from(normalized(observedAt)), targetId);
        return java.util.Objects.requireNonNull(metricId);
    }

    private List<IncidentSeed> fourRuleIncidents(
            int offset,
            long targetId,
            String databaseName,
            long metricId,
            Instant transitionAt
    ) {
        return List.of(
                incident(offset + 1, targetId, databaseName, "CONNECTION_RATIO",
                        "CONNECTION_RATIO_EXCEEDED", metricId, 1L, transitionAt, "connection ratio evidence"),
                incident(offset + 2, targetId, databaseName, "SLOW_QUERY_RATE",
                        "SLOW_QUERIES_HIGH", metricId, 2L, transitionAt, "slow query evidence"),
                incident(offset + 3, targetId, databaseName, "CONNECTION_FAILURE",
                        "CONNECTION_FAILURE", metricId, 3L, transitionAt, "connection failure evidence"),
                incident(offset + 4, targetId, databaseName, "COLLECTION_STALE",
                        "COLLECTION_STALE", metricId, 4L, transitionAt, "collection stale evidence"));
    }

    private IncidentSeed incident(
            int suffix,
            long targetId,
            String databaseName,
            String ruleId,
            String ruleType,
            long metricId,
            long version,
            Instant transitionAt,
            String message
    ) {
        UUID id = new UUID(0L, suffix + 1L);
        String metricName = switch (ruleId) {
            case "CONNECTION_RATIO" -> "activeConnectionsRatio";
            case "SLOW_QUERY_RATE" -> "slowQueriesPerSecond";
            case "CONNECTION_FAILURE" -> "connectionStatus";
            case "COLLECTION_STALE" -> "dataFreshness";
            default -> throw new IllegalArgumentException(ruleId);
        };
        return new IncidentSeed(id, targetId, databaseName, ruleId, ruleType, "CRITICAL",
                normalized(transitionAt.minusSeconds(120)), normalized(transitionAt.minusSeconds(10)),
                metricName, new BigDecimal("0.91"), new BigDecimal("0.90"), metricId,
                UUID.nameUUIDFromBytes((ruleId + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                message, version);
    }

    private void seedIncidentsAndPendingDeliveries(List<IncidentSeed> incidents) {
        Long webhookId = jdbc.queryForObject("""
                INSERT INTO notification_webhooks (
                    name, provider, url_key_version, url_nonce, url_ciphertext,
                    enabled, deleted_at, created_at, updated_at
                ) VALUES (?, 'SLACK', 1, decode(repeat('00', 12), 'hex'),
                          decode(repeat('01', 17), 'hex'), TRUE, NULL, now(), now())
                RETURNING id
                """, Long.class, "fixture-webhook-" + UUID.randomUUID());
        for (IncidentSeed incident : incidents) {
            jdbc.update("""
                    INSERT INTO incidents (
                        incident_id, database_config_id, database_name, rule_id, rule_type, severity,
                        status, opened_at, last_observed_at, resolved_at, resolution_reason,
                        metric_name, metric_value, threshold_value, source_metric_id, source_event_id,
                        message, incident_version
                    ) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?)
                    """, incident.id(), incident.targetId(), incident.databaseName(), incident.ruleId(),
                    incident.ruleType(), incident.severity(), Timestamp.from(incident.openedAt()),
                    Timestamp.from(incident.lastObservedAt()), incident.metricName(), incident.metricValue(),
                    incident.thresholdValue(), incident.sourceMetricId(), incident.sourceEventId(),
                    incident.message(), incident.version());
            jdbc.update("""
                    INSERT INTO risk_rule_states (
                        database_config_id, rule_id, warning_candidate_since, critical_candidate_since,
                        fatal_candidate_since, recovery_since, last_observed_at, last_metric_id, updated_at
                    ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                    ON CONFLICT (database_config_id, rule_id) DO UPDATE SET
                        warning_candidate_since = EXCLUDED.warning_candidate_since,
                        critical_candidate_since = EXCLUDED.critical_candidate_since,
                        last_observed_at = EXCLUDED.last_observed_at,
                        last_metric_id = EXCLUDED.last_metric_id,
                        updated_at = EXCLUDED.updated_at
                    """, incident.targetId(), incident.ruleId(), Timestamp.from(incident.openedAt()),
                    Timestamp.from(incident.openedAt()), Timestamp.from(incident.lastObservedAt()),
                    incident.sourceMetricId(), Timestamp.from(incident.lastObservedAt()));
            jdbc.update("""
                    INSERT INTO notification_deliveries (
                        incident_id, incident_version, notification_type, channel,
                        notification_webhook_id, status, attempt_count, next_attempt_at,
                        expires_at, last_error_code, created_at, sent_at
                    ) VALUES (?, ?, 'INCIDENT_OPENED', 'SLACK', ?, 'PENDING', 0, ?, ?, NULL, ?, NULL)
                    """, incident.id(), incident.version(), webhookId,
                    Timestamp.from(incident.lastObservedAt().plusSeconds(30)),
                    Timestamp.from(incident.lastObservedAt().plusSeconds(600)),
                    Timestamp.from(incident.openedAt()));
        }
    }

    private void assertDefaultPolicy(long targetId) throws Exception {
        Map<String, Object> policy = jdbc.queryForMap("""
                SELECT version, rules::text AS rules, stale_after_seconds, notification_cooldown_seconds
                FROM risk_policies WHERE database_config_id = ?
                """, targetId);
        assertThat(policy.get("version")).isEqualTo(1L);
        assertThat(policy.get("stale_after_seconds")).isEqualTo(30);
        assertThat(policy.get("notification_cooldown_seconds")).isEqualTo(300);
        JsonNode expected = objectMapper.readTree("""
                [
                  {"ruleId":"CONNECTION_RATIO","metricName":"activeConnectionsRatio","operator":"GTE",
                   "warningThreshold":0.80,"criticalThreshold":0.90,"fatalThreshold":0.95,
                   "sustainSeconds":15,"recoverySeconds":15,"enabled":true},
                  {"ruleId":"SLOW_QUERY_RATE","metricName":"slowQueriesPerSecond","operator":"GTE",
                   "warningThreshold":1.0,"criticalThreshold":5.0,"fatalThreshold":null,
                   "sustainSeconds":15,"recoverySeconds":15,"enabled":true}
                ]
                """);
        assertThat(objectMapper.readTree((String) policy.get("rules"))).isEqualTo(expected);
    }

    private void assertState(
            long targetId,
            long configVersion,
            long stateVersion,
            boolean enabled,
            boolean deleted,
            String freshness,
            Instant activationAt,
            Instant updatedAt
    ) {
        Map<String, Object> state = jdbc.queryForMap("SELECT * FROM monitoring_states WHERE database_config_id = ?", targetId);
        assertThat(state.get("config_version")).isEqualTo(configVersion);
        assertThat(state.get("state_version")).isEqualTo(stateVersion);
        assertThat(state.get("enabled")).isEqualTo(enabled);
        assertThat(state.get("deleted")).isEqualTo(deleted);
        assertThat(state.get("connection_status")).isEqualTo("UNKNOWN");
        assertThat(state.get("data_freshness")).isEqualTo(freshness);
        assertThat(state.get("risk_level")).isNull();
        assertThat(state.get("last_attempt_at")).isNull();
        assertThat(state.get("last_success_at")).isNull();
        assertThat(state.get("latest_metric_id")).isNull();
        assertThat(instant(state.get("activation_at"))).isEqualTo(activationAt);
        assertThat(instant(state.get("updated_at"))).isEqualTo(updatedAt);
    }

    private void assertResolved(List<IncidentSeed> expected, String reason, Instant resolvedAt) {
        for (IncidentSeed incident : expected) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM incidents WHERE incident_id = ?", incident.id());
            assertThat(row.get("database_name")).isEqualTo(incident.databaseName());
            assertThat(row.get("rule_id")).isEqualTo(incident.ruleId());
            assertThat(row.get("rule_type")).isEqualTo(incident.ruleType());
            assertThat(row.get("severity")).isEqualTo(incident.severity());
            assertThat(row.get("status")).isEqualTo("RESOLVED");
            assertThat(instant(row.get("opened_at"))).isEqualTo(incident.openedAt());
            assertThat(instant(row.get("last_observed_at"))).isEqualTo(incident.lastObservedAt());
            assertThat(instant(row.get("resolved_at"))).isEqualTo(normalized(resolvedAt));
            assertThat(row.get("resolution_reason")).isEqualTo(reason);
            assertThat(row.get("metric_name")).isEqualTo(incident.metricName());
            assertThat((BigDecimal) row.get("metric_value")).isEqualByComparingTo(incident.metricValue());
            assertThat((BigDecimal) row.get("threshold_value")).isEqualByComparingTo(incident.thresholdValue());
            assertThat(row.get("source_metric_id")).isEqualTo(incident.sourceMetricId());
            assertThat(row.get("source_event_id")).isNull();
            assertThat(row.get("message")).isEqualTo(incident.message());
            assertThat(row.get("incident_version")).isEqualTo(incident.version() + 1L);
        }
    }

    private void assertLedgerOrder(
            List<IncidentSeed> updated,
            List<IncidentSeed> paused,
            List<IncidentSeed> resumed,
            List<IncidentSeed> deleted
    ) {
        List<LedgerRow> rows = ledgerRows();
        assertThat(rows).hasSize(12);
        List<OutboxRow> outbox = outboxRows();
        assertThat(outbox).extracting(OutboxRow::eventId)
                .containsExactlyElementsOf(rows.stream().map(LedgerRow::eventId).toList());
        assertThat(outbox).extracting(OutboxRow::seq).isSorted().doesNotHaveDuplicates();
        assertThat(rows.get(0).eventType()).isEqualTo("MonitoringStatusChangedEvent");
        int index = 1;
        for (List<IncidentSeed> segment : List.of(updated, paused, resumed, deleted)) {
            List<IncidentSeed> ordered = segment.stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
            for (IncidentSeed incident : ordered) {
                LedgerRow row = rows.get(index++);
                assertThat(row.eventType()).isEqualTo("IncidentResolvedEvent");
                assertThat(row.payload().path("incidentId").asText()).isEqualTo(incident.id().toString());
            }
            assertThat(rows.get(index++).eventType()).isEqualTo("MonitoringStatusChangedEvent");
        }
        assertThat(index).isEqualTo(rows.size());
    }

    private void assertExactResolutionPayload(IncidentSeed incident, String reason, Instant resolvedAt) {
        JsonNode payload = ledgerRows().stream()
                .map(LedgerRow::payload)
                .filter(node -> node.path("incidentId").asText().equals(incident.id().toString()))
                .findFirst().orElseThrow();
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
                "schemaVersion", "eventId", "eventType", "publishedAt", "timestamp", "sourceEventId",
                "incidentId", "databaseConfigId", "databaseName", "ruleId", "ruleType", "severity",
                "status", "openedAt", "lastObservedAt", "resolvedAt", "resolutionReason", "metricName",
                "metricValue", "thresholdValue", "sourceMetricId", "message", "incidentVersion");
        assertThat(payload.path("schemaVersion").asInt()).isOne();
        assertThat(payload.path("eventType").asText()).isEqualTo("IncidentResolvedEvent");
        assertThat(payload.path("timestamp").asText()).isEqualTo(time(resolvedAt));
        assertThat(payload.path("sourceEventId").isNull()).isTrue();
        assertThat(payload.path("databaseName").asText()).isEqualTo(incident.databaseName());
        assertThat(payload.path("resolutionReason").asText()).isEqualTo(reason);
        assertThat(payload.path("message").asText()).isEqualTo(incident.message());
        assertThat(payload.path("incidentVersion").asLong()).isEqualTo(incident.version() + 1L);
        assertThat(payload.has("actorId")).isFalse();
        assertThat(payload.has("requestId")).isFalse();
    }

    private void assertLatestStatusPayload(
            long targetId,
            long configVersion,
            long stateVersion,
            boolean enabled,
            boolean deleted,
            String freshness,
            Instant occurredAt
    ) {
        LedgerRow row = ledgerRows().get(ledgerRows().size() - 1);
        assertThat(row.eventType()).isEqualTo("MonitoringStatusChangedEvent");
        assertStatusPayload(row.payload(), targetId, configVersion, stateVersion, enabled, deleted,
                freshness, normalized(occurredAt));
    }

    private void assertStatusPayload(
            JsonNode payload,
            long targetId,
            long configVersion,
            long stateVersion,
            boolean enabled,
            boolean deleted,
            String freshness,
            Instant occurredAt
    ) {
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
                "schemaVersion", "eventId", "eventType", "publishedAt", "databaseConfigId", "configVersion",
                "deleted", "enabled", "connectionStatus", "dataFreshness", "riskLevel", "lastAttemptAt",
                "lastSuccessAt", "latestMetricId", "openIncidentIds", "stateVersion", "updatedAt");
        assertThat(payload.path("schemaVersion").asInt()).isOne();
        assertThat(payload.path("eventType").asText()).isEqualTo("MonitoringStatusChangedEvent");
        assertThat(payload.path("databaseConfigId").asLong()).isEqualTo(targetId);
        assertThat(payload.path("configVersion").asLong()).isEqualTo(configVersion);
        assertThat(payload.path("stateVersion").asLong()).isEqualTo(stateVersion);
        assertThat(payload.path("enabled").asBoolean()).isEqualTo(enabled);
        assertThat(payload.path("deleted").asBoolean()).isEqualTo(deleted);
        assertThat(payload.path("connectionStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(payload.path("dataFreshness").asText()).isEqualTo(freshness);
        assertThat(payload.path("riskLevel").isNull()).isTrue();
        assertThat(payload.path("lastAttemptAt").isNull()).isTrue();
        assertThat(payload.path("lastSuccessAt").isNull()).isTrue();
        assertThat(payload.path("latestMetricId").isNull()).isTrue();
        assertThat(payload.path("openIncidentIds").isEmpty()).isTrue();
        assertThat(payload.path("updatedAt").asText()).isEqualTo(time(occurredAt));
        assertThat(payload.has("actorId")).isFalse();
        assertThat(payload.has("requestId")).isFalse();
    }

    private void assertOutboxEnvelope(OutboxRow row, long targetId, Instant occurredAt) {
        String expectedStream = switch (row.eventType()) {
            case "MonitoringStatusChangedEvent" -> "stream:statuses";
            case "IncidentResolvedEvent" -> "stream:incidents";
            default -> throw new IllegalArgumentException(row.eventType());
        };
        assertThat(row.seq()).isPositive();
        assertThat(row.streamKey()).isEqualTo(expectedStream);
        assertThat(row.orderingKey()).isEqualTo("database:" + targetId);
        assertThat(row.payload().path("schemaVersion").asInt()).isOne();
        assertThat(row.payload().path("eventId").asText()).isEqualTo(row.eventId().toString());
        assertThat(row.payload().path("eventType").asText()).isEqualTo(row.eventType());
        assertThat(row.createdAt()).isNotEqualTo(occurredAt);
        assertThat(row.publishedAt()).isNull();
        assertThat(row.attempts()).isZero();
        assertThat(row.nextAttemptAt()).isEqualTo(row.createdAt());
        assertThat(row.lastError()).isNull();
        assertThat(row.payload().path("publishedAt").asText()).isEqualTo(time(row.createdAt()));
        String occurrenceField = row.eventType().equals("IncidentResolvedEvent") ? "resolvedAt" : "updatedAt";
        assertThat(row.payload().path(occurrenceField).asText()).isEqualTo(time(occurredAt));
    }

    private void assertDeletedTarget(long targetId) {
        Map<String, Object> target = jdbc.queryForMap("""
                SELECT name, enabled, config_version, deleted_at
                FROM database_configs WHERE id = ?
                """, targetId);
        assertThat(target.get("name")).isEqualTo("renamed-target");
        assertThat(target.get("enabled")).isEqualTo(false);
        assertThat(target.get("config_version")).isEqualTo(5L);
        assertThat(target.get("deleted_at")).isNotNull();
    }

    private void assertAllPendingCancelled(long expected) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                WHERE status = 'CANCELLED' AND next_attempt_at IS NULL
                """, Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries WHERE status = 'PENDING'
                """, Long.class)).isZero();
    }

    private void assertAllStoreCountsZero() {
        for (String table : List.of(
                "database_configs", "audit_logs", "monitoring_states", "risk_policies", "incidents",
                "risk_rule_states", "push_subscriptions", "notification_webhooks",
                "notification_deliveries", "event_outbox", "lifecycle_outbox_insert_ledger")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).as(table).isZero();
        }
    }

    private DatabaseSnapshot snapshot() {
        return new DatabaseSnapshot(
                rows("database_configs", "id"),
                rows("audit_logs", "id"),
                rows("monitoring_states", "database_config_id"),
                rows("risk_policies", "database_config_id"),
                rows("incidents", "incident_id"),
                rows("risk_rule_states", "database_config_id, rule_id"),
                rows("push_subscriptions", "id"),
                rows("notification_webhooks", "id"),
                rows("notification_deliveries", "id"),
                rows("event_outbox", "event_id"),
                rows("lifecycle_outbox_insert_ledger", "sequence"));
    }

    private String rows(String table, String orderBy) {
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(ordered_rows)), '[]'::jsonb)::text "
                + "FROM (SELECT * FROM " + table + " ORDER BY " + orderBy + ") ordered_rows", String.class);
    }

    private List<OutboxRow> outboxRows() {
        return jdbc.query("""
                SELECT event_id, seq, event_type, stream_key, ordering_key, payload::text AS payload,
                       created_at, published_at, attempts, next_attempt_at, last_error
                FROM event_outbox ORDER BY seq
                """, (row, ignored) -> new OutboxRow(
                row.getObject("event_id", UUID.class),
                row.getLong("seq"),
                row.getString("event_type"),
                row.getString("stream_key"),
                row.getString("ordering_key"),
                readJson(row.getString("payload")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("published_at") == null ? null : row.getTimestamp("published_at").toInstant(),
                row.getInt("attempts"),
                row.getTimestamp("next_attempt_at").toInstant(),
                row.getString("last_error")));
    }

    private List<LedgerRow> ledgerRows() {
        return jdbc.query("""
                SELECT sequence, event_id, event_type, payload::text AS payload
                FROM lifecycle_outbox_insert_ledger ORDER BY sequence
                """, (row, ignored) -> new LedgerRow(
                row.getLong("sequence"),
                row.getObject("event_id", UUID.class),
                row.getString("event_type"),
                readJson(row.getString("payload"))));
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('public.' || ?) IS NOT NULL", Boolean.class, table));
    }

    private boolean hasConstraint(String table, String constraint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_constraint c
                    JOIN pg_class t ON t.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                    WHERE n.nspname = 'public' AND t.relname = ? AND c.conname = ?
                )
                """, Boolean.class, table, constraint));
    }

    private Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private Instant at(long minute) {
        return Instant.parse("2026-09-29T10:00:00.123456789Z").plusSeconds(minute * 60L);
    }

    private Instant normalized(Instant value) {
        return value.truncatedTo(ChronoUnit.MILLIS);
    }

    private String time(Instant value) {
        return UtcInstantJacksonConfig.format(normalized(value));
    }

    private UUID requestId(long version) {
        return new UUID(0x701L, version & Long.MAX_VALUE);
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    private void proof(String scenario, String... observables) {
        System.out.println("LIFECYCLE_POSTGRES_PROOF scenario=" + scenario + " " + String.join(" ", observables));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private record IncidentSeed(
            UUID id,
            long targetId,
            String databaseName,
            String ruleId,
            String ruleType,
            String severity,
            Instant openedAt,
            Instant lastObservedAt,
            String metricName,
            BigDecimal metricValue,
            BigDecimal thresholdValue,
            long sourceMetricId,
            UUID sourceEventId,
            String message,
            long version
    ) {
    }

    private record OutboxRow(
            UUID eventId,
            long seq,
            String eventType,
            String streamKey,
            String orderingKey,
            JsonNode payload,
            Instant createdAt,
            Instant publishedAt,
            int attempts,
            Instant nextAttemptAt,
            String lastError
    ) {
    }

    private record LedgerRow(long sequence, UUID eventId, String eventType, JsonNode payload) {
    }

    private record DatabaseSnapshot(
            String targets,
            String audits,
            String states,
            String policies,
            String incidents,
            String ruleStates,
            String pushSubscriptions,
            String webhooks,
            String deliveries,
            String outbox,
            String ledger
    ) {
    }
}
