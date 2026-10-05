package com.example.monitoring.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class PartCHttpDatabaseFixture {

    private final JdbcTemplate jdbc;

    PartCHttpDatabaseFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long currentPolicyVersion(long databaseConfigId) {
        return jdbc.queryForObject("SELECT version FROM risk_policies WHERE database_config_id=?",
                Long.class, databaseConfigId);
    }

    void seedIncident(UUID id, long databaseConfigId, String ruleId, String ruleType,
                      String severity, String status, Instant openedAt,
                      String resolutionReason, long version) {
        boolean resolved = "RESOLVED".equals(status);
        String metricName = switch (ruleId) {
            case "CONNECTION_RATIO" -> "activeConnectionsRatio";
            case "SLOW_QUERY_RATE" -> "slowQueriesPerSecond";
            case "CONNECTION_FAILURE" -> "connectionStatus";
            case "COLLECTION_STALE" -> "collectionAgeSeconds";
            default -> throw new IllegalArgumentException("Unknown rule " + ruleId);
        };
        jdbc.update("""
                INSERT INTO incidents (
                    incident_id,database_config_id,database_name,rule_id,rule_type,severity,status,
                    opened_at,last_observed_at,resolved_at,resolution_reason,metric_name,metric_value,
                    threshold_value,source_metric_id,source_event_id,message,incident_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,?,?)
                """, id, databaseConfigId, "part-c-contract-target", ruleId, ruleType, severity, status,
                Timestamp.from(openedAt), Timestamp.from(openedAt),
                resolved ? Timestamp.from(openedAt.plusMillis(1)) : null,
                resolutionReason, metricName,
                ruleId.equals("CONNECTION_FAILURE") ? null : 1.25,
                ruleId.equals("CONNECTION_FAILURE") ? null : 1.0,
                "contract incident " + id, version);
    }

    void seedRuleClock(long databaseConfigId, String ruleId, Instant observedAt) {
        jdbc.update("""
                INSERT INTO risk_rule_states (
                    database_config_id,rule_id,warning_candidate_since,critical_candidate_since,
                    fatal_candidate_since,recovery_since,last_observed_at,last_metric_id,updated_at)
                VALUES (?,?,?,NULL,NULL,NULL,?,NULL,?)
                """, databaseConfigId, ruleId, Timestamp.from(observedAt), Timestamp.from(observedAt),
                Timestamp.from(observedAt));
    }

    long seedWebhook(String name, boolean enabled) {
        return jdbc.queryForObject("""
                INSERT INTO notification_webhooks (
                    name,provider,url_key_version,url_nonce,url_ciphertext,enabled,created_at,updated_at)
                VALUES (?,'SLACK',1,?,?,?,transaction_timestamp(),transaction_timestamp()) RETURNING id
                """, Long.class, name, new byte[12], new byte[17], enabled);
    }

    long seedDelivery(UUID incidentId, long incidentVersion, String channel, long recipientId,
                      String status, Instant createdAt, Instant sentAt, String lastErrorCode) {
        boolean pending = "PENDING".equals(status);
        boolean push = "WEB_PUSH".equals(channel);
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries (
                    incident_id,incident_version,notification_type,channel,push_subscription_id,
                    notification_webhook_id,status,attempt_count,next_attempt_at,expires_at,
                    last_error_code,created_at,sent_at)
                VALUES (?,?,'INCIDENT_OPENED',?,?,?,?,0,?,?,?,?,?) RETURNING id
                """, Long.class, incidentId, incidentVersion, channel,
                push ? recipientId : null, push ? null : recipientId, status,
                pending ? Timestamp.from(createdAt) : null,
                Timestamp.from(createdAt.plusSeconds(600)), lastErrorCode,
                Timestamp.from(createdAt), sentAt == null ? null : Timestamp.from(sentAt));
    }

    void assertIncidentResolution(UUID incidentId, String status, String reason, long version) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status,resolution_reason,incident_version,resolved_at
                FROM incidents WHERE incident_id=?
                """, incidentId);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("resolution_reason")).isEqualTo(reason);
        assertThat(((Number) row.get("incident_version")).longValue()).isEqualTo(version);
        if ("OPEN".equals(status)) assertThat(row.get("resolved_at")).isNull();
        else assertThat(row.get("resolved_at")).isNotNull();
    }

    void assertDeliveryState(long deliveryId, String status, boolean hasNextAttempt) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,next_attempt_at,last_error_code FROM notification_deliveries WHERE id=?",
                deliveryId);
        assertThat(row.get("status")).isEqualTo(status);
        if (hasNextAttempt) assertThat(row.get("next_attempt_at")).isNotNull();
        else assertThat(row.get("next_attempt_at")).isNull();
        if ("CANCELLED".equals(status)) assertThat(row.get("last_error_code")).isNull();
    }

    void assertDeliveryNotSent(long deliveryId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,sent_at FROM notification_deliveries WHERE id=?", deliveryId);
        assertThat(row.get("status")).isNotEqualTo("SENT");
        assertThat(row.get("sent_at")).isNull();
    }

    long activePushCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM push_subscriptions "
                + "WHERE user_id=? AND enabled AND deleted_at IS NULL", Long.class, userId);
    }

    BusinessSnapshot snapshot() {
        return new BusinessSnapshot(
                jsonRows("SELECT * FROM risk_policies ORDER BY database_config_id"),
                jsonRows("SELECT * FROM monitoring_states ORDER BY database_config_id"),
                jsonRows("SELECT * FROM incidents ORDER BY incident_id"),
                jsonRows("SELECT * FROM risk_rule_states ORDER BY database_config_id,rule_id"),
                jsonRows("SELECT * FROM push_subscriptions ORDER BY id"),
                jsonRows("SELECT * FROM notification_webhooks ORDER BY id"),
                jsonRows("SELECT * FROM notification_deliveries ORDER BY id"),
                jsonRows("SELECT * FROM event_outbox ORDER BY seq"));
    }

    void installAuditFailureTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION part_c_test_reject_policy_success_audit()
                RETURNS trigger AS $$
                BEGIN
                    IF NEW.action = 'POLICY_UPDATED' AND NEW.result = 'SUCCESS' THEN
                        RAISE EXCEPTION 'task19 injected audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER part_c_test_reject_policy_success_audit_trigger
                BEFORE INSERT ON audit_logs
                FOR EACH ROW EXECUTE FUNCTION part_c_test_reject_policy_success_audit()
                """);
    }

    void dropAuditFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS part_c_test_reject_policy_success_audit_trigger ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS part_c_test_reject_policy_success_audit()");
    }

    void truncate() {
        jdbc.execute("""
                TRUNCATE TABLE
                    processed_events,
                    notification_deliveries,
                    push_subscriptions,
                    notification_webhooks,
                    risk_rule_states,
                    incidents,
                    risk_policies,
                    monitoring_states,
                    event_outbox,
                    metric_data,
                    blocked_reasons,
                    access_logs,
                    audit_logs,
                    used_refresh_tokens,
                    auth_sessions,
                    database_configs,
                    users
                RESTART IDENTITY CASCADE
                """);
    }

    private String jsonRows(String query) {
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row_value)),'[]'::jsonb)::text "
                + "FROM (" + query + ") row_value", String.class);
    }

    record BusinessSnapshot(
            String policies,
            String states,
            String incidents,
            String ruleStates,
            String pushes,
            String webhooks,
            String deliveries,
            String outbox
    ) {
    }
}
