package com.example.monitoring.notification.persistence;

import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.notification.api.DeliveryResponse;
import com.example.monitoring.notification.api.DeliveryStatus;
import com.example.monitoring.notification.api.NotificationChannel;
import com.example.monitoring.partc.api.PartCPage;
import com.example.monitoring.partc.api.PartCQueryWindow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRecipientRepository {

    private static final int WEBHOOK_ADVISORY_NAMESPACE = 807_199;
    private static final int WEBHOOK_ADVISORY_KEY = 10;

    private final JdbcTemplate jdbc;

    public NotificationRecipientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockEndpoint(byte[] endpointHash) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(encode(?::bytea, 'hex'), 0))",
                endpointHash);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PushRow> findActivePushByHashForUpdate(byte[] endpointHash) {
        return jdbc.query("""
                SELECT id, user_id, sid, expiration_time, created_at, updated_at
                FROM push_subscriptions
                WHERE endpoint_hash = ? AND enabled AND deleted_at IS NULL
                FOR UPDATE
                """, (result, row) -> pushRow(result), endpointHash).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int countActivePush(long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM push_subscriptions
                WHERE user_id = ? AND enabled AND deleted_at IS NULL
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long nextPushId() {
        return jdbc.queryForObject(
                "SELECT nextval('push_subscriptions_id_seq')", Long.class);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PushRow insertPush(
            long id,
            long userId,
            UUID sid,
            byte[] endpointHash,
            EncryptedValue encrypted,
            Long expirationTime,
            Instant now
    ) {
        jdbc.update("""
                INSERT INTO push_subscriptions
                    (id, user_id, sid, endpoint_hash, payload_key_version, payload_nonce,
                     payload_ciphertext, expiration_time, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                """, id, userId, sid, endpointHash, encrypted.keyVersion(), encrypted.nonce(),
                encrypted.ciphertext(), expirationTime, Timestamp.from(now), Timestamp.from(now));
        return new PushRow(id, userId, sid, expirationTime, now, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PushRow updatePush(
            PushRow existing,
            UUID sid,
            EncryptedValue encrypted,
            Long expirationTime,
            Instant now
    ) {
        jdbc.update("""
                UPDATE push_subscriptions
                SET sid = ?, payload_key_version = ?, payload_nonce = ?, payload_ciphertext = ?,
                    expiration_time = ?, enabled = true, deleted_at = NULL, updated_at = ?
                WHERE id = ?
                """, sid, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext(),
                expirationTime, Timestamp.from(now), existing.id());
        return new PushRow(
                existing.id(), existing.userId(), sid, expirationTime, existing.createdAt(), now);
    }

    public List<PushRow> listActivePush(long userId) {
        return jdbc.query("""
                SELECT id, user_id, sid, expiration_time, created_at, updated_at
                FROM push_subscriptions
                WHERE user_id = ? AND enabled AND deleted_at IS NULL
                ORDER BY id
                """, (result, row) -> pushRow(result), userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PushRow> findOwnedPushForUpdate(long id, long userId) {
        return jdbc.query("""
                SELECT id, user_id, sid, expiration_time, created_at, updated_at
                FROM push_subscriptions
                WHERE id = ? AND user_id = ? AND enabled AND deleted_at IS NULL
                FOR UPDATE
                """, (result, row) -> pushRow(result), id, userId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPendingPushDeliveries(long id) {
        jdbc.query("""
                SELECT id
                FROM notification_deliveries
                WHERE push_subscription_id = ? AND status = 'PENDING'
                ORDER BY id
                FOR UPDATE
                """, result -> null, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void tombstonePush(long id, Instant now) {
        jdbc.update("""
                UPDATE push_subscriptions
                SET enabled = false, deleted_at = ?, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        cancelPendingPush(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingPush(long id) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, last_error_code = NULL
                WHERE push_subscription_id = ? AND status = 'PENDING'
                """, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockWebhookNamespace() {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(?, ?)",
                WEBHOOK_ADVISORY_NAMESPACE, WEBHOOK_ADVISORY_KEY);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int countNonDeletedWebhooks() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM notification_webhooks WHERE deleted_at IS NULL
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long nextWebhookId() {
        return jdbc.queryForObject(
                "SELECT nextval('notification_webhooks_id_seq')", Long.class);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WebhookRow insertWebhook(
            long id,
            String name,
            EncryptedValue encrypted,
            boolean enabled,
            Instant now
    ) {
        jdbc.update("""
                INSERT INTO notification_webhooks
                    (id, name, provider, url_key_version, url_nonce, url_ciphertext,
                     enabled, created_at, updated_at)
                VALUES (?, ?, 'SLACK', ?, ?, ?, ?, ?, ?)
                """, id, name, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext(),
                enabled, Timestamp.from(now), Timestamp.from(now));
        return new WebhookRow(id, name, "SLACK", enabled, now, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WebhookSecretRow> findWebhookForUpdate(long id) {
        return jdbc.query("""
                SELECT id, name, provider, url_key_version, url_nonce, url_ciphertext,
                       enabled, created_at, updated_at
                FROM notification_webhooks
                WHERE id = ? AND deleted_at IS NULL
                FOR UPDATE
                """, (result, row) -> new WebhookSecretRow(
                result.getLong("id"),
                result.getString("name"),
                result.getString("provider"),
                result.getInt("url_key_version"),
                result.getBytes("url_nonce"),
                result.getBytes("url_ciphertext"),
                result.getBoolean("enabled"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()), id).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPendingWebhookDeliveries(long id) {
        jdbc.query("""
                SELECT id
                FROM notification_deliveries
                WHERE notification_webhook_id = ? AND status = 'PENDING'
                ORDER BY id
                FOR UPDATE
                """, result -> null, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WebhookRow updateWebhook(
            WebhookSecretRow existing,
            String name,
            EncryptedValue encrypted,
            boolean enabled,
            Instant now
    ) {
        EncryptedValue value = encrypted == null
                ? new EncryptedValue(
                        existing.keyVersion(), existing.nonce(), existing.ciphertext())
                : encrypted;
        jdbc.update("""
                UPDATE notification_webhooks
                SET name = ?, url_key_version = ?, url_nonce = ?, url_ciphertext = ?,
                    enabled = ?, updated_at = ?
                WHERE id = ?
                """, name, value.keyVersion(), value.nonce(), value.ciphertext(), enabled,
                Timestamp.from(now), existing.id());
        if (!enabled) {
            cancelPendingWebhook(existing.id());
        }
        return new WebhookRow(
                existing.id(), name, existing.provider(), enabled, existing.createdAt(), now);
    }

    public PageSlice<WebhookRow> listWebhooks(PartCPage page) {
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM notification_webhooks WHERE deleted_at IS NULL", Long.class);
        List<WebhookRow> items = jdbc.query("""
                SELECT id, name, provider, enabled, created_at, updated_at
                FROM notification_webhooks
                WHERE deleted_at IS NULL
                ORDER BY id
                LIMIT ? OFFSET ?
                """, (result, row) -> new WebhookRow(
                result.getLong("id"),
                result.getString("name"),
                result.getString("provider"),
                result.getBoolean("enabled"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()),
                page.size(), page.offset());
        return new PageSlice<>(items, total);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tombstoneWebhook(long id, Instant now) {
        Optional<WebhookSecretRow> existing = findWebhookForUpdate(id);
        if (existing.isEmpty()) {
            return false;
        }
        jdbc.update("""
                UPDATE notification_webhooks
                SET enabled = false, deleted_at = ?, updated_at = ?
                WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        cancelPendingWebhook(id);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingWebhook(long id) {
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, last_error_code = NULL
                WHERE notification_webhook_id = ? AND status = 'PENDING'
                """, id);
    }

    public PageSlice<DeliveryResponse> listDeliveries(
            UUID incidentId,
            NotificationChannel channel,
            DeliveryStatus status,
            PartCQueryWindow window,
            PartCPage page
    ) {
        StringBuilder where = new StringBuilder(" WHERE created_at >= ? AND created_at < ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(window.start()));
        arguments.add(Timestamp.from(window.end()));
        if (incidentId != null) {
            where.append(" AND incident_id = ?");
            arguments.add(incidentId);
        }
        if (channel != null) {
            where.append(" AND channel = ?");
            arguments.add(channel.name());
        }
        if (status != null) {
            where.append(" AND status = ?");
            arguments.add(status.name());
        }

        long total = jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries" + where,
                Long.class,
                arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(page.size());
        pageArguments.add(page.offset());
        List<DeliveryResponse> items = jdbc.query("""
                        SELECT id, incident_id, incident_version, channel, recipient_id, status,
                               attempt_count, last_error_code, created_at, sent_at
                        FROM notification_deliveries
                        """ + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (result, row) -> new DeliveryResponse(
                        result.getLong("id"),
                        result.getObject("incident_id", UUID.class),
                        result.getLong("incident_version"),
                        NotificationChannel.valueOf(result.getString("channel")),
                        result.getLong("recipient_id"),
                        DeliveryStatus.valueOf(result.getString("status")),
                        result.getInt("attempt_count"),
                        result.getString("last_error_code"),
                        result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("sent_at") == null
                                ? null : result.getTimestamp("sent_at").toInstant()),
                pageArguments.toArray());
        return new PageSlice<>(items, total);
    }

    private PushRow pushRow(java.sql.ResultSet result) throws java.sql.SQLException {
        return new PushRow(
                result.getLong("id"),
                result.getLong("user_id"),
                result.getObject("sid", UUID.class),
                (Long) result.getObject("expiration_time"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    public record PushRow(
            long id,
            long userId,
            UUID sid,
            Long expirationTime,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record WebhookRow(
            long id,
            String name,
            String provider,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record WebhookSecretRow(
            long id,
            String name,
            String provider,
            int keyVersion,
            byte[] nonce,
            byte[] ciphertext,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        public WebhookSecretRow {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    public record PageSlice<T>(List<T> items, long total) {
        public PageSlice {
            items = List.copyOf(items);
        }
    }
}
