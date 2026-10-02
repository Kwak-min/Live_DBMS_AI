package com.example.monitoring.risk.persistence;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.common.outbox.ProcessedEventStore;
import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.contract.RiskLevel;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.StatusSnapshot;
import com.example.monitoring.risk.engine.CollectionOutcome;
import com.example.monitoring.risk.engine.MetricRiskObservation;
import com.example.monitoring.risk.engine.RiskStateMachine;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.engine.RuleClock;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        RiskOutboxAppender.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RiskPersistenceIntegrationTest {

    private static final long TARGET_ID = 12L;
    private static final UUID SOURCE_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final UUID INCIDENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000222");
    private static final UUID INCIDENT_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000333");
    private static final UUID STATUS_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000444");
    private static final Instant ACTIVATED_AT = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant DUE_AT = ACTIVATED_AT.plusSeconds(30);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventStore processedEvents;

    @Autowired
    private RiskJdbcStore store;

    @Autowired
    private RiskOutboxAppender outbox;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() throws Exception {
        transactions = new TransactionTemplate(transactionManager);
        cleanup();
        seedTargetStateAndPolicy();
    }

    @AfterEach
    void tearDown() {
        dropFailureTrigger();
        cleanup();
    }

    @Test
    void atomicTransition() {
        transactions.executeWithoutResult(ignored -> persistStaleTransition());

        assertThat(appliedMigrations()).containsExactly("1", "2", "3", "4");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE status='OPEN'", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT risk_level FROM monitoring_states WHERE database_config_id=?",
                String.class, TARGET_ID)).isEqualTo("CRITICAL");
        assertThat(jdbc.queryForList("SELECT event_type FROM event_outbox ORDER BY seq", String.class))
                .containsExactly("IncidentCreatedEvent", "MonitoringStatusChangedEvent");
        assertThat(jdbc.queryForList(
                "SELECT event_id FROM processed_events WHERE stream='stream:metrics' AND consumer_group='cg:risk'",
                UUID.class)).containsExactly(SOURCE_EVENT_ID);
    }

    @Test
    void outboxFailureRollsBackEverything() {
        String before = snapshot();
        installFailureTrigger();

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> persistStaleTransition()))
                .isInstanceOf(RuntimeException.class);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();
    }

    @Test
    void mutationBeansAreProxiedAndRequireCallerTransaction() {
        assertThat(AopUtils.isAopProxy(store)).isTrue();
        assertThat(AopUtils.isAopProxy(outbox)).isTrue();
        assertThatThrownBy(() -> store.lockTarget(TARGET_ID))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> outbox.appendStatus(STATUS_EVENT_ID, status()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void repeatStaleCandidateRemainsDueWithOpenIncident() {
        transactions.executeWithoutResult(ignored -> store.insertIncident(incident(), null));
        jdbc.update("""
                UPDATE monitoring_states
                SET state_version=2, data_freshness='FRESH', last_attempt_at=?, updated_at=?
                WHERE database_config_id=?
                """, Timestamp.from(ACTIVATED_AT.plusSeconds(25)),
                Timestamp.from(ACTIVATED_AT.plusSeconds(31)), TARGET_ID);

        assertThat(store.findStaleCandidates(ACTIVATED_AT.plusSeconds(54), 100)).isEmpty();
        assertThat(store.findStaleCandidates(ACTIVATED_AT.plusSeconds(55), 100))
                .containsExactly(new StaleCandidate(TARGET_ID, ACTIVATED_AT.plusSeconds(55)));
    }

    @Test
    void lateFreshMetricDoesNotRewindStaleIncidentEvidence() {
        transactions.executeWithoutResult(ignored -> store.insertIncident(incident(), null));

        transactions.executeWithoutResult(ignored -> {
            RiskMutationLock locked = store.lockForMutation(TARGET_ID).orElseThrow();
            assertThat(locked.openIncidents()).singleElement()
                    .extracting(Incident::lastObservedAt)
                    .isEqualTo(DUE_AT);
            assertThat(store.touchOpenIncidents(TARGET_ID, ACTIVATED_AT.plusSeconds(25)))
                    .isOne();
        });

        assertThat(jdbc.queryForObject(
                "SELECT last_observed_at FROM incidents WHERE incident_id=?",
                Timestamp.class,
                INCIDENT_ID).toInstant()).isEqualTo(DUE_AT);
    }

    @Test
    void databaseConstraintAllowsOnlyOneOpenIncidentPerRule() {
        Incident duplicate = incident(UUID.fromString(
                "00000000-0000-0000-0000-000000000555"));

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            store.insertIncident(incident(), null);
            store.insertIncident(duplicate, null);
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents", Long.class)).isZero();
    }

    @Test
    void safeStateVersionCannotOverflow() {
        jdbc.update("""
                UPDATE monitoring_states SET state_version=? WHERE database_config_id=?
                """, RiskState.MAX_SAFE_INTEGER, TARGET_ID);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            RiskMutationLock locked = store.lockForMutation(TARGET_ID).orElseThrow();
            new RiskStateMachine().evaluate(
                    locked.engineSnapshot(),
                    new MetricRiskObservation(
                            1L,
                            SOURCE_EVENT_ID,
                            ACTIVATED_AT.plusSeconds(1),
                            ACTIVATED_AT.plusSeconds(1),
                            CollectionOutcome.SUCCESS,
                            new java.math.BigDecimal("0.10"),
                            java.math.BigDecimal.ZERO));
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stateVersion");
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM monitoring_states WHERE database_config_id=?",
                Long.class,
                TARGET_ID)).isEqualTo(RiskState.MAX_SAFE_INTEGER);
    }

    private void persistStaleTransition() {
        RiskMutationLock locked = store.lockForMutation(TARGET_ID).orElseThrow();
        assertThat(locked.target().databaseConfigId()).isEqualTo(TARGET_ID);
        assertThat(locked.state().stateVersion()).isOne();
        assertThat(locked.policy().version()).isOne();
        assertThat(locked.openIncidents()).isEmpty();
        assertThat(locked.pendingDeliveries()).isEmpty();
        assertThat(processedEvents.markProcessed("stream:metrics", "cg:risk", SOURCE_EVENT_ID)).isTrue();

        RuleClock staleClock = new RuleClock(
                RuleId.COLLECTION_STALE, null, DUE_AT, null, null, DUE_AT, null);
        Incident incident = incident();
        RiskState state = staleState();
        store.saveRuleClock(TARGET_ID, staleClock, DUE_AT);
        store.insertIncident(incident, null);
        store.updateState(state);
        outbox.appendIncident(
                INCIDENT_EVENT_ID,
                OutboxEventType.INCIDENT_CREATED,
                IncidentEventPayload.created(incident, DUE_AT, null));
        outbox.appendStatus(STATUS_EVENT_ID, status());
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
                """, TARGET_ID, objectMapper.writeValueAsString(MonitoringContracts.defaultRules()),
                Timestamp.from(ACTIVATED_AT), Timestamp.from(ACTIVATED_AT));
    }

    private Incident incident() {
        return incident(INCIDENT_ID);
    }

    private Incident incident(UUID incidentId) {
        return new Incident(
                incidentId,
                TARGET_ID,
                "production",
                RuleId.COLLECTION_STALE,
                RuleType.COLLECTION_STALE,
                IncidentSeverity.CRITICAL,
                IncidentStatus.OPEN,
                DUE_AT,
                DUE_AT,
                null,
                null,
                "collectionAgeSeconds",
                new java.math.BigDecimal("30"),
                new java.math.BigDecimal("30"),
                null,
                "Collection is stale",
                1L);
    }

    private RiskState staleState() {
        return new RiskState(
                TARGET_ID,
                1L,
                2L,
                true,
                false,
                ConnectionStatus.UNKNOWN,
                DataFreshness.STALE,
                RiskLevel.CRITICAL,
                ACTIVATED_AT,
                null,
                null,
                null,
                DUE_AT);
    }

    private StatusSnapshot status() {
        return new StatusSnapshot(
                TARGET_ID,
                1L,
                false,
                true,
                ConnectionStatus.UNKNOWN,
                DataFreshness.STALE,
                RiskLevel.CRITICAL,
                null,
                null,
                null,
                List.of(INCIDENT_ID),
                2L,
                DUE_AT);
    }

    private List<String> appliedMigrations() {
        return java.util.Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().getVersion())
                .toList();
    }

    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'state', (SELECT to_jsonb(s) FROM monitoring_states s WHERE database_config_id=12),
                    'rules', (SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY rule_id), '[]'::jsonb)
                              FROM risk_rule_states r WHERE database_config_id=12),
                    'incidents', (SELECT COALESCE(jsonb_agg(to_jsonb(i) ORDER BY incident_id), '[]'::jsonb)
                                  FROM incidents i WHERE database_config_id=12),
                    'processed', (SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY event_id), '[]'::jsonb)
                                  FROM processed_events p),
                    'outbox', (SELECT COALESCE(jsonb_agg(to_jsonb(o) ORDER BY seq), '[]'::jsonb)
                               FROM event_outbox o)
                )::text
                """, String.class);
    }

    private void installFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_risk_status_outbox() RETURNS trigger AS $$
                BEGIN
                  IF NEW.event_type = 'MonitoringStatusChangedEvent' THEN
                    RAISE EXCEPTION 'forced risk outbox failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_risk_status_outbox_trigger
                BEFORE INSERT ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_risk_status_outbox()
                """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_risk_status_outbox_trigger ON event_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_risk_status_outbox()");
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
}
