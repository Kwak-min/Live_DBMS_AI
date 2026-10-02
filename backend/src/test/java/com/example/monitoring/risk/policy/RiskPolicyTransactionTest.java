package com.example.monitoring.risk.policy;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.common.web.AuditRequestContext;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskOutboxAppender;
import com.example.monitoring.service.AuditEventService;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({
        EmbeddedPostgresSupport.Config.class,
        UtcInstantJacksonConfig.class,
        OutboxWriter.class,
        RiskJdbcStore.class,
        RiskOutboxAppender.class,
        RiskPolicyQueryRepository.class,
        RiskPolicyService.class,
        AuditEventService.class,
        RiskPolicyTransactionTest.Config.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RiskPolicyTransactionTest {

    private static final long TARGET_ID = 12L;
    private static final Instant NOW = Instant.parse("2026-09-28T12:34:56.789Z");
    private static final UUID CONFIG_A = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CONFIG_B = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID SYSTEM_A = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID SYSTEM_B = UUID.fromString("00000000-0000-0000-0000-000000000040");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RiskPolicyService service;

    @BeforeEach
    void setUp() throws Exception {
        cleanup();
        seedTarget();
    }

    @AfterEach
    void tearDown() {
        dropAuditFailureTrigger();
        cleanup();
    }

    @Test
    void commitsSelectiveClosureCancellationOrderedOutboxAndAuditAtomically() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(service.get("12").version()).isOne();

        RiskPolicy updated = service.update("12", write(1L));

        assertThat(updated.version()).isEqualTo(2L);
        RiskPolicy persisted = service.get("12");
        assertThat(persisted.version()).isEqualTo(2L);
        assertThat(persisted.staleAfterSeconds()).isEqualTo(120);
        assertThat(persisted.notificationCooldownSeconds()).isEqualTo(600);
        assertThat(persisted.rules()).extracting(rule -> rule.ruleId().name())
                .containsExactly("CONNECTION_RATIO", "SLOW_QUERY_RATE");
        assertThat(jdbc.queryForList("""
                SELECT rule_id, status, resolution_reason, source_event_id, incident_version, message
                FROM incidents
                WHERE database_config_id = 12
                ORDER BY incident_id
                """)).containsExactly(
                row("CONNECTION_RATIO", "RESOLVED", "POLICY_CHANGED", null, 4L,
                        "evidence-CONNECTION_RATIO"),
                row("SLOW_QUERY_RATE", "RESOLVED", "POLICY_CHANGED", null, 8L,
                        "evidence-SLOW_QUERY_RATE"),
                row("CONNECTION_FAILURE", "OPEN", null, null, 2L,
                        "evidence-CONNECTION_FAILURE"),
                row("COLLECTION_STALE", "OPEN", null, null, 9L,
                        "evidence-COLLECTION_STALE"));
        assertThat(jdbc.queryForList("""
                SELECT rule_id FROM risk_rule_states
                WHERE database_config_id = 12 ORDER BY rule_id
                """, String.class)).containsExactly("COLLECTION_STALE", "CONNECTION_FAILURE");
        assertThat(jdbc.queryForList("""
                SELECT incident_id, status, next_attempt_at
                FROM notification_deliveries ORDER BY incident_id
                """)).containsExactly(
                delivery(CONFIG_A, "CANCELLED", null),
                delivery(CONFIG_B, "CANCELLED", null),
                delivery(SYSTEM_A, "PENDING", Timestamp.from(NOW.plusSeconds(60))));
        assertThat(jdbc.queryForMap("""
                SELECT state_version, risk_level, updated_at
                FROM monitoring_states WHERE database_config_id = 12
                """)).containsEntry("state_version", 11L)
                .containsEntry("risk_level", "FATAL")
                .containsEntry("updated_at", Timestamp.from(NOW));
        assertThat(jdbc.queryForList(
                "SELECT event_type FROM event_outbox ORDER BY seq", String.class))
                .containsExactly(
                        "IncidentResolvedEvent",
                        "IncidentResolvedEvent",
                        "MonitoringStatusChangedEvent");
        assertThat(jdbc.queryForList("""
                SELECT payload->>'incidentId' FROM event_outbox
                WHERE event_type = 'IncidentResolvedEvent' ORDER BY seq
                """, String.class)).containsExactly(CONFIG_A.toString(), CONFIG_B.toString());
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'openIncidentIds' FROM event_outbox
                WHERE event_type = 'MonitoringStatusChangedEvent'
                """, String.class)).contains(SYSTEM_A.toString(), SYSTEM_B.toString());
        assertThat(jdbc.queryForMap("""
                SELECT action, target_type, target_id, database_config_id, result, summary
                FROM audit_logs
                """)).containsEntry("action", "POLICY_UPDATED")
                .containsEntry("target_type", "POLICY")
                .containsEntry("target_id", "12")
                .containsEntry("database_config_id", 12L)
                .containsEntry("result", "SUCCESS")
                .containsEntry("summary", "Risk policy updated to version 2");
    }

    @Test
    void auditInsertFailureRollsBackPolicyIncidentsDeliveriesStateAndOutbox() {
        String before = snapshot();
        installAuditFailureTrigger();

        assertThatThrownBy(() -> service.update("12", write(1L)))
                .isInstanceOf(RuntimeException.class);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
    }

    @Test
    void getReturnsPersistedPolicyButHidesDeletedAndMissingTargets() {
        assertThat(service.get("12").version()).isOne();

        jdbc.update("UPDATE database_configs SET deleted_at = ? WHERE id = 12", Timestamp.from(NOW));
        assertThatThrownBy(() -> service.get("12"))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus().value()).isEqualTo(404);
                    assertThat(failure.getCode()).isEqualTo("DATABASE_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.get("999"))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("DATABASE_NOT_FOUND"));
    }

    private PolicyWrite write(long version) {
        return new PolicyWrite(
                version,
                120,
                600,
                MonitoringContracts.defaultRules().stream().map(PolicyRuleWrite::from).toList());
    }

    private void seedTarget() throws Exception {
        jdbc.update("""
                INSERT INTO database_configs
                    (id, collection_interval_seconds, created_at, enabled, host, name, port, status,
                     config_version, deleted_at)
                VALUES (?, 5, ?, true, '127.0.0.1', 'production', 13306, 'UNKNOWN', 3, null)
                """, TARGET_ID, Timestamp.from(NOW.minusSeconds(3600)));
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, risk_level, activation_at,
                     last_attempt_at, last_success_at, latest_metric_id, updated_at)
                VALUES (?, 3, 10, true, false, 'UP', 'FRESH', 'FATAL', ?, ?, ?, null, ?)
                """, TARGET_ID,
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW.minusSeconds(10)),
                Timestamp.from(NOW.minusSeconds(10)),
                Timestamp.from(NOW.minusSeconds(10)));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, CAST(? AS jsonb), 30, 300, ?, ?)
                """, TARGET_ID,
                objectMapper.writeValueAsString(MonitoringContracts.defaultRules()),
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW.minusSeconds(3600)));
        seedRuleClock("CONNECTION_RATIO");
        seedRuleClock("SLOW_QUERY_RATE");
        seedRuleClock("CONNECTION_FAILURE");
        seedRuleClock("COLLECTION_STALE");
        seedIncident(CONFIG_A, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED", "CRITICAL", 3L);
        seedIncident(CONFIG_B, "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH", "WARNING", 7L);
        seedIncident(SYSTEM_A, "CONNECTION_FAILURE", "CONNECTION_FAILURE", "FATAL", 2L);
        seedIncident(SYSTEM_B, "COLLECTION_STALE", "COLLECTION_STALE", "CRITICAL", 9L);
        jdbc.update("""
                INSERT INTO notification_webhooks
                    (id, name, provider, url_key_version, url_nonce, url_ciphertext,
                     enabled, deleted_at, created_at, updated_at)
                VALUES (91, 'operations', 'SLACK', 1, ?, ?, true, null, ?, ?)
                """, new byte[12], new byte[17],
                Timestamp.from(NOW.minusSeconds(300)), Timestamp.from(NOW.minusSeconds(300)));
        seedDelivery(CONFIG_A, 3L);
        seedDelivery(CONFIG_B, 7L);
        seedDelivery(SYSTEM_A, 2L);
    }

    private void seedRuleClock(String ruleId) {
        jdbc.update("""
                INSERT INTO risk_rule_states
                    (database_config_id, rule_id, critical_candidate_since,
                     last_observed_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, TARGET_ID, ruleId,
                Timestamp.from(NOW.minusSeconds(30)),
                Timestamp.from(NOW.minusSeconds(10)),
                Timestamp.from(NOW.minusSeconds(10)));
    }

    private void seedIncident(UUID id, String ruleId, String ruleType, String severity, long version) {
        String metricName = switch (ruleId) {
            case "CONNECTION_RATIO" -> "activeConnectionsRatio";
            case "SLOW_QUERY_RATE" -> "slowQueriesPerSecond";
            case "CONNECTION_FAILURE" -> "connectionStatus";
            case "COLLECTION_STALE" -> "collectionAgeSeconds";
            default -> throw new IllegalArgumentException("unexpected rule");
        };
        jdbc.update("""
                INSERT INTO incidents
                    (incident_id, database_config_id, database_name, rule_id, rule_type,
                     severity, status, opened_at, last_observed_at, resolved_at,
                     resolution_reason, metric_name, metric_value, threshold_value,
                     source_metric_id, source_event_id, message, incident_version)
                VALUES (?, ?, 'production', ?, ?, ?, 'OPEN', ?, ?, null, null, ?, ?, ?, null, null, ?, ?)
                """, id, TARGET_ID, ruleId, ruleType, severity,
                Timestamp.from(NOW.minusSeconds(120)), Timestamp.from(NOW.minusSeconds(10)),
                metricName, new java.math.BigDecimal("0.91"), new java.math.BigDecimal("0.90"),
                "evidence-" + ruleId, version);
    }

    private void seedDelivery(UUID incidentId, long incidentVersion) {
        jdbc.update("""
                INSERT INTO notification_deliveries
                    (incident_id, incident_version, notification_type, channel,
                     notification_webhook_id, status, attempt_count, next_attempt_at,
                     expires_at, created_at)
                VALUES (?, ?, 'INCIDENT_OPENED', 'SLACK', 91, 'PENDING', 0, ?, ?, ?)
                """, incidentId, incidentVersion,
                Timestamp.from(NOW.plusSeconds(60)), Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.minusSeconds(60)));
    }

    private java.util.Map<String, Object> row(
            String ruleId,
            String status,
            String reason,
            UUID sourceEventId,
            long version,
            String message
    ) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("rule_id", ruleId);
        row.put("status", status);
        row.put("resolution_reason", reason);
        row.put("source_event_id", sourceEventId);
        row.put("incident_version", version);
        row.put("message", message);
        return row;
    }

    private java.util.Map<String, Object> delivery(UUID incidentId, String status, Timestamp nextAttemptAt) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("incident_id", incidentId);
        row.put("status", status);
        row.put("next_attempt_at", nextAttemptAt);
        return row;
    }

    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'policy', (SELECT to_jsonb(p) FROM risk_policies p WHERE database_config_id=12),
                    'state', (SELECT to_jsonb(s) FROM monitoring_states s WHERE database_config_id=12),
                    'rules', (SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY rule_id), '[]'::jsonb)
                              FROM risk_rule_states r WHERE database_config_id=12),
                    'incidents', (SELECT COALESCE(jsonb_agg(to_jsonb(i) ORDER BY incident_id), '[]'::jsonb)
                                  FROM incidents i WHERE database_config_id=12),
                    'deliveries', (SELECT COALESCE(jsonb_agg(to_jsonb(d) ORDER BY id), '[]'::jsonb)
                                   FROM notification_deliveries d),
                    'outbox', (SELECT COALESCE(jsonb_agg(to_jsonb(o) ORDER BY seq), '[]'::jsonb)
                               FROM event_outbox o),
                    'audit', (SELECT COALESCE(jsonb_agg(to_jsonb(a) ORDER BY id), '[]'::jsonb)
                              FROM audit_logs a)
                )::text
                """, String.class);
    }

    private void installAuditFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_policy_audit() RETURNS trigger AS $$
                BEGIN
                  IF NEW.action = 'POLICY_UPDATED' THEN
                    RAISE EXCEPTION 'forced policy audit failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_policy_audit_trigger
                BEFORE INSERT ON audit_logs
                FOR EACH ROW EXECUTE FUNCTION fail_policy_audit()
                """);
    }

    private void dropAuditFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_policy_audit_trigger ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_policy_audit()");
    }

    private void cleanup() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM risk_rule_states");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM risk_policies");
        jdbc.update("DELETE FROM monitoring_states");
        jdbc.update("DELETE FROM metric_data");
        jdbc.update("DELETE FROM database_configs");
    }

    @TestConfiguration
    static class Config {

        @Bean
        Clock partCClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        PartCQueryValidator partCQueryValidator(Clock clock) {
            return new PartCQueryValidator(clock);
        }

        @Bean
        AuditRequestContext auditRequestContext() {
            AuditRequestContext context = mock(AuditRequestContext.class);
            when(context.current()).thenReturn(new AuditRequestContext.Details(
                    7L, "127.0.0.1", UUID.fromString("00000000-0000-0000-0000-000000000777")));
            return context;
        }
    }
}
