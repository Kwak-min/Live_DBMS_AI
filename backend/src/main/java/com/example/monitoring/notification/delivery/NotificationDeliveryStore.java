package com.example.monitoring.notification.delivery;

import com.example.monitoring.notification.api.DeliveryStatus;
import com.example.monitoring.notification.api.NotificationChannel;
import com.example.monitoring.notification.transport.NotificationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationDeliveryStore {

    private final JdbcTemplate jdbc;

    public NotificationDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DueCandidate> dueCandidates(Instant now, int limit) {
        return jdbc.query("""
                SELECT delivery.id, delivery.incident_id, incident.database_config_id
                FROM notification_deliveries AS delivery
                JOIN incidents AS incident ON incident.incident_id = delivery.incident_id
                WHERE delivery.status = 'PENDING'
                  AND delivery.next_attempt_at <= ?
                ORDER BY delivery.next_attempt_at, delivery.id
                LIMIT ?
                """, (result, row) -> new DueCandidate(
                result.getLong("id"), result.getObject("incident_id", UUID.class),
                result.getLong("database_config_id")),
                Timestamp.from(now), limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TargetRow> lockTarget(long targetId) {
        return jdbc.query("""
                SELECT enabled, deleted_at IS NOT NULL AS deleted
                FROM database_configs
                WHERE id = ?
                FOR UPDATE
                """, (result, row) -> new TargetRow(
                result.getBoolean("enabled"), result.getBoolean("deleted")), targetId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<TargetRow> readTarget(long targetId) {
        return jdbc.query("""
                SELECT enabled, deleted_at IS NOT NULL AS deleted
                FROM database_configs
                WHERE id = ?
                """, (result, row) -> new TargetRow(
                result.getBoolean("enabled"), result.getBoolean("deleted")), targetId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StateRow> lockState(long targetId) {
        return jdbc.query("""
                SELECT enabled, deleted
                FROM monitoring_states
                WHERE database_config_id = ?
                FOR UPDATE
                """, (result, row) -> new StateRow(
                result.getBoolean("enabled"), result.getBoolean("deleted")), targetId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<StateRow> readState(long targetId) {
        return jdbc.query("""
                SELECT enabled, deleted
                FROM monitoring_states
                WHERE database_config_id = ?
                """, (result, row) -> new StateRow(
                result.getBoolean("enabled"), result.getBoolean("deleted")), targetId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IncidentRow> lockIncident(UUID incidentId) {
        return jdbc.query("""
                SELECT incident_id, database_config_id, database_name, rule_id, severity,
                       status, opened_at, last_observed_at, resolved_at, resolution_reason,
                       incident_version
                FROM incidents
                WHERE incident_id = ?
                FOR UPDATE
                """, (result, row) -> incident(result), incidentId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<IncidentRow> readIncident(UUID incidentId) {
        return jdbc.query("""
                SELECT incident_id, database_config_id, database_name, rule_id, severity,
                       status, opened_at, last_observed_at, resolved_at, resolution_reason,
                       incident_version
                FROM incidents
                WHERE incident_id = ?
                """, (result, row) -> incident(result), incidentId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeliveryRow> lockDelivery(long deliveryId) {
        return jdbc.query("""
                SELECT id, incident_id, incident_version, notification_type, channel,
                       push_subscription_id, notification_webhook_id, status, attempt_count,
                       next_attempt_at, expires_at, last_error_code, created_at
                FROM notification_deliveries
                WHERE id = ?
                FOR UPDATE
                """, (result, row) -> delivery(result), deliveryId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<DeliveryRow> readDelivery(long deliveryId) {
        return jdbc.query("""
                SELECT id, incident_id, incident_version, notification_type, channel,
                       push_subscription_id, notification_webhook_id, status, attempt_count,
                       next_attempt_at, expires_at, last_error_code, created_at
                FROM notification_deliveries
                WHERE id = ?
                """, (result, row) -> delivery(result), deliveryId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PushRecipientRow> readPush(long pushId) {
        return jdbc.query("""
                SELECT id, user_id, sid, payload_key_version, payload_nonce, payload_ciphertext,
                       expiration_time, enabled, deleted_at
                FROM push_subscriptions
                WHERE id = ?
                """, (result, row) -> new PushRecipientRow(
                result.getLong("id"),
                result.getLong("user_id"),
                result.getObject("sid", UUID.class),
                result.getInt("payload_key_version"),
                result.getBytes("payload_nonce"),
                result.getBytes("payload_ciphertext"),
                (Long) result.getObject("expiration_time"),
                result.getBoolean("enabled"),
                result.getTimestamp("deleted_at") != null), pushId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WebhookRecipientRow> readWebhook(long webhookId) {
        return jdbc.query("""
                SELECT id, url_key_version, url_nonce, url_ciphertext, enabled, deleted_at
                FROM notification_webhooks
                WHERE id = ?
                """, (result, row) -> new WebhookRecipientRow(
                result.getLong("id"),
                result.getInt("url_key_version"),
                result.getBytes("url_nonce"),
                result.getBytes("url_ciphertext"),
                result.getBoolean("enabled"),
                result.getTimestamp("deleted_at") != null), webhookId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(long deliveryId, long expectedIncidentVersion, int expectedAttempts,
                         int claimedAttempt, Instant nextAttempt) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET attempt_count = ?, next_attempt_at = ?
                WHERE id = ?
                  AND incident_version = ?
                  AND status = 'PENDING'
                  AND attempt_count = ?
                """, claimedAttempt, Timestamp.from(nextAttempt), deliveryId,
                expectedIncidentVersion, expectedAttempts) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markSent(long deliveryId, long incidentVersion, int claimedAttempt, Instant sentAt) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'SENT', next_attempt_at = NULL, last_error_code = NULL, sent_at = ?
                WHERE id = ? AND incident_version = ? AND status = 'PENDING' AND attempt_count = ?
                """, Timestamp.from(sentAt), deliveryId, incidentVersion, claimedAttempt) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertSuccessReceipt(
            UUID incidentId,
            NotificationChannel channel,
            long recipientId,
            Instant successfulAt
    ) {
        jdbc.update("""
                INSERT INTO notification_success_receipts
                    (incident_id, channel, push_subscription_id, notification_webhook_id,
                     last_successful_open_or_increase_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (incident_id, channel, recipient_id) DO UPDATE
                SET last_successful_open_or_increase_at = GREATEST(
                    notification_success_receipts.last_successful_open_or_increase_at,
                    EXCLUDED.last_successful_open_or_increase_at)
                """,
                incidentId,
                channel.name(),
                channel == NotificationChannel.WEB_PUSH ? recipientId : null,
                channel == NotificationChannel.SLACK ? recipientId : null,
                Timestamp.from(successfulAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean createRecoveryDelivery(
            UUID incidentId,
            long incidentVersion,
            NotificationChannel channel,
            long recipientId,
            Instant resolvedAt,
            Instant expiresAt,
            Instant createdAt
    ) {
        return jdbc.update("""
                INSERT INTO notification_deliveries
                    (incident_id, incident_version, notification_type, channel,
                     push_subscription_id, notification_webhook_id, status, attempt_count,
                     next_attempt_at, expires_at, created_at)
                VALUES (?, ?, 'INCIDENT_RESOLVED', ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                incidentId,
                incidentVersion,
                channel.name(),
                channel == NotificationChannel.WEB_PUSH ? recipientId : null,
                channel == NotificationChannel.SLACK ? recipientId : null,
                Timestamp.from(resolvedAt),
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt)) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markFailed(long deliveryId, long incidentVersion, int claimedAttempt, String errorCode) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'FAILED', next_attempt_at = NULL, last_error_code = ?, sent_at = NULL
                WHERE id = ? AND incident_version = ? AND status = 'PENDING' AND attempt_count = ?
                """, errorCode, deliveryId, incidentVersion, claimedAttempt) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRetry(long deliveryId, long incidentVersion, int claimedAttempt,
                             Instant nextAttempt, String errorCode) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET next_attempt_at = ?, last_error_code = ?, sent_at = NULL
                WHERE id = ? AND incident_version = ? AND status = 'PENDING' AND attempt_count = ?
                """, Timestamp.from(nextAttempt), errorCode,
                deliveryId, incidentVersion, claimedAttempt) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(long deliveryId, Long incidentVersion, Integer claimedAttempt) {
        StringBuilder sql = new StringBuilder("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, last_error_code = NULL, sent_at = NULL
                WHERE id = ? AND status = 'PENDING'
                """);
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(deliveryId);
        if (incidentVersion != null) {
            sql.append(" AND incident_version = ?");
            arguments.add(incidentVersion);
        }
        if (claimedAttempt != null) {
            sql.append(" AND attempt_count = ?");
            arguments.add(claimedAttempt);
        }
        return jdbc.update(sql.toString(), arguments.toArray()) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelExpired(
            long deliveryId,
            long incidentVersion,
            int claimedAttempt,
            String errorCode
    ) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL,
                    last_error_code = ?, sent_at = NULL
                WHERE id = ? AND incident_version = ? AND status = 'PENDING' AND attempt_count = ?
                """, errorCode, deliveryId, incidentVersion, claimedAttempt) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivatePush(
            long pushId,
            int keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            Instant now
    ) {
        jdbc.update("""
                UPDATE push_subscriptions
                SET enabled = false, deleted_at = ?, updated_at = ?
                WHERE id = ? AND enabled AND deleted_at IS NULL
                  AND payload_key_version = ?
                  AND payload_nonce = ?
                  AND payload_ciphertext = ?
                """, Timestamp.from(now), Timestamp.from(now), pushId,
                keyVersion, nonce, ciphertext);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivateWebhook(
            long webhookId,
            int keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            Instant now
    ) {
        jdbc.update("""
                UPDATE notification_webhooks
                SET enabled = false, deleted_at = ?, updated_at = ?
                WHERE id = ? AND enabled AND deleted_at IS NULL
                  AND url_key_version = ?
                  AND url_nonce = ?
                  AND url_ciphertext = ?
                """, Timestamp.from(now), Timestamp.from(now), webhookId,
                keyVersion, nonce, ciphertext);
    }

    public void verifyPrerequisites() {
        String missing = jdbc.queryForObject("""
                SELECT string_agg(required.name, ', ' ORDER BY required.name)
                FROM (VALUES
                    ('database_configs'), ('monitoring_states'), ('incidents'),
                    ('push_subscriptions'), ('notification_webhooks'), ('notification_deliveries'),
                    ('notification_success_receipts')
                ) AS required(name)
                WHERE to_regclass(required.name) IS NULL
                """, String.class);
        if (missing != null) {
            throw new IllegalStateException("Notification delivery requires tables: " + missing);
        }
    }

    private IncidentRow incident(ResultSet result) throws SQLException {
        return new IncidentRow(
                result.getObject("incident_id", UUID.class),
                result.getLong("database_config_id"),
                result.getString("database_name"),
                result.getString("rule_id"),
                result.getString("severity"),
                result.getString("status"),
                result.getTimestamp("opened_at").toInstant(),
                result.getTimestamp("last_observed_at").toInstant(),
                instant(result, "resolved_at"),
                result.getString("resolution_reason"),
                result.getLong("incident_version"));
    }

    private DeliveryRow delivery(ResultSet result) throws SQLException {
        return new DeliveryRow(
                result.getLong("id"),
                result.getObject("incident_id", UUID.class),
                result.getLong("incident_version"),
                NotificationType.valueOf(result.getString("notification_type")),
                NotificationChannel.valueOf(result.getString("channel")),
                nullableLong(result, "push_subscription_id"),
                nullableLong(result, "notification_webhook_id"),
                DeliveryStatus.valueOf(result.getString("status")),
                result.getInt("attempt_count"),
                instant(result, "next_attempt_at"),
                result.getTimestamp("expires_at").toInstant(),
                result.getString("last_error_code"),
                result.getTimestamp("created_at").toInstant());
    }

    private Instant instant(ResultSet result, String name) throws SQLException {
        Timestamp value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String name) throws SQLException {
        long value = result.getLong(name);
        return result.wasNull() ? null : value;
    }

    public record DueCandidate(long deliveryId, UUID incidentId, long targetId) { }

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

    public record IncidentRow(
            UUID incidentId,
            long targetId,
            String databaseName,
            String ruleId,
            String severity,
            String status,
            Instant openedAt,
            Instant lastObservedAt,
            Instant resolvedAt,
            String resolutionReason,
            long version
    ) { }

    public record DeliveryRow(
            long id,
            UUID incidentId,
            long incidentVersion,
            NotificationType type,
            NotificationChannel channel,
            Long pushId,
            Long webhookId,
            DeliveryStatus status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant expiresAt,
            String lastErrorCode,
            Instant createdAt
    ) { }

    public record PushRecipientRow(
            long id,
            long userId,
            UUID sessionId,
            int keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            Long expirationTime,
            boolean enabled,
            boolean deleted
    ) {
        public PushRecipientRow {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }

    public record WebhookRecipientRow(
            long id,
            int keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            boolean enabled,
            boolean deleted
    ) {
        public WebhookRecipientRow {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
}
