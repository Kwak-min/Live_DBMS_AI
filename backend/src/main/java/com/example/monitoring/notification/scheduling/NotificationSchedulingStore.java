package com.example.monitoring.notification.scheduling;

import com.example.monitoring.common.stream.InvariantStreamRecordException;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RuleId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationSchedulingStore {

    private final JdbcTemplate jdbc;

    public NotificationSchedulingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TargetRow> lockTarget(long databaseConfigId) {
        return jdbc.query("""
                SELECT enabled, deleted_at IS NOT NULL AS deleted
                FROM database_configs
                WHERE id = ?
                FOR UPDATE
                """, (result, row) -> new TargetRow(
                result.getBoolean("enabled"), result.getBoolean("deleted")), databaseConfigId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StateRow lockState(long databaseConfigId, UUID eventId) {
        return jdbc.query("""
                SELECT enabled, deleted
                FROM monitoring_states
                WHERE database_config_id = ?
                FOR UPDATE
                """, (result, row) -> new StateRow(
                result.getBoolean("enabled"), result.getBoolean("deleted")), databaseConfigId)
                .stream().findFirst()
                .orElseThrow(() -> missingDependency("monitoring state", eventId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PolicyRow lockPolicy(long databaseConfigId, UUID eventId) {
        return jdbc.query("""
                SELECT notification_cooldown_seconds
                FROM risk_policies
                WHERE database_config_id = ?
                FOR UPDATE
                """, (result, row) -> new PolicyRow(
                result.getInt("notification_cooldown_seconds")), databaseConfigId)
                .stream().findFirst()
                .orElseThrow(() -> missingDependency("risk policy", eventId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IncidentRow> lockIncident(UUID incidentId) {
        return jdbc.query("""
                SELECT database_config_id, rule_id, severity, status, resolution_reason, incident_version
                FROM incidents
                WHERE incident_id = ?
                FOR UPDATE
                """, (result, row) -> new IncidentRow(
                result.getLong("database_config_id"),
                RuleId.valueOf(result.getString("rule_id")),
                IncidentSeverity.valueOf(result.getString("severity")),
                IncidentStatus.valueOf(result.getString("status")),
                result.getString("resolution_reason") == null
                        ? null : ResolutionReason.valueOf(result.getString("resolution_reason")),
                result.getLong("incident_version")), incidentId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockDeliveries(UUID incidentId) {
        jdbc.query("""
                SELECT id
                FROM notification_deliveries
                WHERE incident_id = ?
                ORDER BY id
                FOR UPDATE
                """, result -> null, incidentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Recipient> activeRecipients(Instant now, Instant eventOccurredAt) {
        return jdbc.query("""
                SELECT channel, recipient_id
                FROM (
                    SELECT 'WEB_PUSH' AS channel, push.id AS recipient_id
                    FROM push_subscriptions AS push
                    JOIN users AS account ON account.id = push.user_id
                    JOIN auth_sessions AS session
                      ON session.sid = push.sid AND session.user_id = push.user_id
                    WHERE push.enabled
                      AND push.deleted_at IS NULL
                      AND push.created_at <= ?
                      AND (push.expiration_time IS NULL OR push.expiration_time > ?)
                      AND account.enabled
                      AND session.revoked_at IS NULL
                      AND session.expires_at > ?
                      AND session.auth_version = account.auth_version
                    UNION ALL
                    SELECT 'SLACK' AS channel, webhook.id AS recipient_id
                    FROM notification_webhooks AS webhook
                    WHERE webhook.enabled
                      AND webhook.deleted_at IS NULL
                      AND webhook.created_at <= ?
                ) AS recipients
                ORDER BY channel, recipient_id
                """, (result, row) -> new Recipient(
                Channel.valueOf(result.getString("channel")), result.getLong("recipient_id")),
                Timestamp.from(eventOccurredAt), now.toEpochMilli(), Timestamp.from(now),
                Timestamp.from(eventOccurredAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> lastSuccessfulOpenOrIncrease(UUID incidentId, Recipient recipient) {
        return jdbc.query("""
                SELECT last_successful_open_or_increase_at
                FROM notification_success_receipts
                WHERE incident_id = ?
                  AND channel = ?
                  AND ((channel = 'WEB_PUSH' AND push_subscription_id = ?)
                    OR (channel = 'SLACK' AND notification_webhook_id = ?))
                """, (result, row) -> result
                        .getTimestamp("last_successful_open_or_increase_at").toInstant(),
                incidentId, recipient.channel().name(), recipient.id(), recipient.id())
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PendingDelivery> pendingIncrease(UUID incidentId, Recipient recipient) {
        return jdbc.query("""
                SELECT id, incident_version, next_attempt_at, expires_at, created_at
                FROM notification_deliveries
                WHERE incident_id = ?
                  AND notification_type = 'SEVERITY_INCREASED'
                  AND status = 'PENDING'
                  AND channel = ?
                  AND ((channel = 'WEB_PUSH' AND push_subscription_id = ?)
                    OR (channel = 'SLACK' AND notification_webhook_id = ?))
                ORDER BY created_at, id
                LIMIT 1
                """, (result, row) -> new PendingDelivery(
                result.getLong("id"),
                result.getLong("incident_version"),
                result.getTimestamp("next_attempt_at").toInstant(),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("created_at").toInstant()),
                incidentId, recipient.channel().name(), recipient.id(), recipient.id())
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void mergePending(long deliveryId, long incidentVersion) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET incident_version = ?
                WHERE id = ? AND status = 'PENDING'
                """, incidentVersion, deliveryId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelDelivery(long deliveryId) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, last_error_code = NULL
                WHERE id = ? AND status = 'PENDING'
                """, deliveryId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingIncreases(UUID incidentId) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, last_error_code = NULL
                WHERE incident_id = ?
                  AND notification_type = 'SEVERITY_INCREASED'
                  AND status = 'PENDING'
                """, incidentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingOpenOrIncrease(UUID incidentId) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, last_error_code = NULL
                WHERE incident_id = ?
                  AND notification_type IN ('INCIDENT_OPENED', 'SEVERITY_INCREASED')
                  AND status = 'PENDING'
                """, incidentId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasSuccessfulOpenOrIncrease(UUID incidentId, Recipient recipient) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM notification_success_receipts
                    WHERE incident_id = ?
                      AND channel = ?
                      AND ((channel = 'WEB_PUSH' AND push_subscription_id = ?)
                        OR (channel = 'SLACK' AND notification_webhook_id = ?))
                )
                """, Boolean.class, incidentId, recipient.channel().name(), recipient.id(), recipient.id());
        return Boolean.TRUE.equals(exists);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createDelivery(
            UUID incidentId,
            long incidentVersion,
            NotificationType type,
            Recipient recipient,
            Instant eligibleAt,
            Instant expiresAt,
            Instant createdAt
    ) {
        jdbc.update("""
                INSERT INTO notification_deliveries
                    (incident_id, incident_version, notification_type, channel,
                     push_subscription_id, notification_webhook_id, status, attempt_count,
                     next_attempt_at, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                incidentId,
                incidentVersion,
                type.name(),
                recipient.channel().name(),
                recipient.channel() == Channel.WEB_PUSH ? recipient.id() : null,
                recipient.channel() == Channel.SLACK ? recipient.id() : null,
                Timestamp.from(eligibleAt),
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt));
    }

    public void verifyPrerequisites() {
        String missing = jdbc.queryForObject("""
                SELECT string_agg(required.name, ', ' ORDER BY required.name)
                FROM (VALUES
                    ('database_configs'), ('monitoring_states'), ('risk_policies'), ('incidents'),
                    ('push_subscriptions'), ('notification_webhooks'), ('notification_deliveries'),
                    ('notification_success_receipts'), ('processed_events')
                ) AS required(name)
                WHERE to_regclass(required.name) IS NULL
                """, String.class);
        if (missing != null) {
            throw new IllegalStateException("Notification scheduling requires tables: " + missing);
        }
    }

    private InvariantStreamRecordException missingDependency(String dependency, UUID eventId) {
        return new InvariantStreamRecordException(
                "MISSING_NOTIFICATION_DEPENDENCY",
                "Incident event references a missing " + dependency,
                eventId);
    }

    public enum Channel {
        WEB_PUSH,
        SLACK
    }

    public record TargetRow(boolean enabled, boolean deleted) {
        public boolean active() {
            return enabled && !deleted;
        }
    }

    public record StateRow(boolean enabled, boolean deleted) {
        public boolean active() {
            return enabled && !deleted;
        }
    }

    public record PolicyRow(int cooldownSeconds) {
    }

    public record IncidentRow(
            long databaseConfigId,
            RuleId ruleId,
            IncidentSeverity severity,
            IncidentStatus status,
            ResolutionReason resolutionReason,
            long incidentVersion
    ) {
    }

    public record Recipient(Channel channel, long id) {
    }

    public record PendingDelivery(
            long id,
            long incidentVersion,
            Instant nextAttemptAt,
            Instant expiresAt,
            Instant createdAt
    ) {
    }
}
