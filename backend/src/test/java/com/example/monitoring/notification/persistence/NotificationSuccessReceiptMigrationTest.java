package com.example.monitoring.notification.persistence;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationSuccessReceiptMigrationTest {

    private static final String KEYS = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Map<String, String> V1_TO_V4_HASHES = Map.of(
            "backend/src/main/resources/db/migration/V1__baseline_existing_schema.sql",
            "9126D4AF2EA3FA216C13EBE5FECE2C93511DC669F3817C05E9BF13040508D297",
            "backend/src/main/java/db/migration/V2__part_b_auth_and_encrypt_database_credentials.java",
            "290F3CF48C0A7AC7155E71014DA34ED1B3A180283738E163C568B20CFC6EFF16",
            "backend/src/main/java/db/migration/V3__part_a_metrics_and_outbox.java",
            "4CCC34A3F413CD187D7BD3C699A9127ADE577FE30178DA4E2DE3E777717FA6DF",
            "backend/src/main/resources/db/migration/V4__part_c_monitoring.sql",
            "63D04D8FDBF1E3D9F0E5684AFE460B2F5E3291E5B7261025EB2600EDA3F70ADB");

    private static String previousActiveKey;
    private static String previousKeys;
    private static String previousTimeZone;
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        previousActiveKey = System.getProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
        previousKeys = System.getProperty("DB_CONFIG_ENCRYPTION_KEYS");
        previousTimeZone = System.getProperty("LEGACY_TIME_ZONE");
        System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
        System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", "{\"1\":\"" + KEYS + "\"}");
        System.setProperty("LEGACY_TIME_ZONE", "UTC");
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
        restore("DB_CONFIG_ACTIVE_KEY_VERSION", previousActiveKey);
        restore("DB_CONFIG_ENCRYPTION_KEYS", previousKeys);
        restore("LEGACY_TIME_ZONE", previousTimeZone);
    }

    @Test
    void v5BackfillsLatestOpeningSuccessForEachChannelAndRecipient() throws Exception {
        Database database = createV4Database();
        try {
            Seed seed = seedParents(database.dataSource());
            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            Instant created = Instant.parse("2026-01-01T00:00:00Z");
            Instant first = created.plusSeconds(10);
            Instant latest = created.plusSeconds(20);
            Instant slack = created.plusSeconds(30);
            Instant resolved = created.plusSeconds(40);

            insertDelivery(jdbc, seed.openIncident(), 1, "INCIDENT_OPENED", "WEB_PUSH",
                    seed.pushId(), null, "SENT", created, first);
            insertDelivery(jdbc, seed.openIncident(), 2, "SEVERITY_INCREASED", "WEB_PUSH",
                    seed.pushId(), null, "SENT", created, latest);
            insertDelivery(jdbc, seed.openIncident(), 3, "INCIDENT_OPENED", "SLACK",
                    null, seed.webhookId(), "SENT", created, slack);
            insertDelivery(jdbc, seed.resolvedIncident(), 1, "SEVERITY_INCREASED", "SLACK",
                    null, seed.webhookId(), "SENT", created, resolved);
            insertDelivery(jdbc, seed.openIncident(), 4, "INCIDENT_RESOLVED", "WEB_PUSH",
                    seed.pushId(), null, "SENT", created, created.plusSeconds(50));
            insertDelivery(jdbc, seed.openIncident(), 5, "SEVERITY_INCREASED", "WEB_PUSH",
                    seed.pushId(), null, "FAILED", created, null);
            insertDelivery(jdbc, seed.openIncident(), 6, "INCIDENT_OPENED", "SLACK",
                    null, seed.webhookId(), "CANCELLED", created, null);

            assertThat(Flyway.configure().dataSource(database.dataSource()).target("5").load()
                    .migrate().migrationsExecuted).isOne();

            List<Map<String, Object>> receipts = jdbc.queryForList("""
                    SELECT incident_id, channel, recipient_id,
                           last_successful_open_or_increase_at
                    FROM notification_success_receipts
                    ORDER BY incident_id, channel
                    """);
            assertThat(receipts).hasSize(3);
            assertReceipt(receipts, seed.openIncident(), "WEB_PUSH", seed.pushId(), latest);
            assertReceipt(receipts, seed.openIncident(), "SLACK", seed.webhookId(), slack);
            assertReceipt(receipts, seed.resolvedIncident(), "SLACK", seed.webhookId(), resolved);
            assertThat(seed.pushId()).isEqualTo(seed.webhookId());
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v5RejectsInvalidRecipientShapesReferencesNullsAndUnsafeIds() throws Exception {
        Database database = createV4Database();
        try {
            Seed seed = seedParents(database.dataSource());
            Flyway.configure().dataSource(database.dataSource()).load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            String successfulAt = "'2026-01-01T00:00:10Z'";

            assertRejected(jdbc, """
                    INSERT INTO notification_success_receipts
                        (incident_id, channel, notification_webhook_id,
                         last_successful_open_or_increase_at)
                    VALUES ('%s', 'EMAIL', %d, %s)
                    """.formatted(seed.openIncident(), seed.webhookId(), successfulAt));
            assertRejected(jdbc, """
                    INSERT INTO notification_success_receipts
                        (incident_id, channel, push_subscription_id, notification_webhook_id,
                         last_successful_open_or_increase_at)
                    VALUES ('%s', 'WEB_PUSH', %d, %d, %s)
                    """.formatted(seed.openIncident(), seed.pushId(), seed.webhookId(), successfulAt));
            assertRejected(jdbc, """
                    INSERT INTO notification_success_receipts
                        (incident_id, channel, notification_webhook_id,
                         last_successful_open_or_increase_at)
                    VALUES ('%s', 'SLACK', %d, %s)
                    """.formatted(UUID.randomUUID(), seed.webhookId(), successfulAt));
            assertRejected(jdbc, """
                    INSERT INTO notification_success_receipts
                        (incident_id, channel, notification_webhook_id,
                         last_successful_open_or_increase_at)
                    VALUES ('%s', 'SLACK', 9007199254740991, %s)
                    """.formatted(seed.openIncident(), successfulAt));
            assertRejected(jdbc, """
                    INSERT INTO notification_success_receipts
                        (incident_id, channel, notification_webhook_id,
                         last_successful_open_or_increase_at)
                    VALUES ('%s', 'SLACK', %d, NULL)
                    """.formatted(seed.openIncident(), seed.webhookId()));

            try (Connection connection = database.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO notification_success_receipts
                            (incident_id, channel, push_subscription_id,
                             last_successful_open_or_increase_at)
                        VALUES ('%s', 'WEB_PUSH', 9007199254740992, %s)
                        """.formatted(seed.openIncident(), successfulAt)))
                        .hasMessageContaining("notification_success_receipts_recipient_id_safe_check");
                statement.execute("SET session_replication_role = origin");
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v1ThroughV4CanonicalLfContentRemainsByteIdentical() throws Exception {
        Path repository = locateRepository();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, String> expected : V1_TO_V4_HASHES.entrySet()) {
            byte[] bytes = canonicalLf(Files.readAllBytes(repository.resolve(expected.getKey())));
            actual.put(expected.getKey(), java.util.HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(V1_TO_V4_HASHES);
    }

    private static byte[] canonicalLf(byte[] bytes) {
        java.io.ByteArrayOutputStream normalized = new java.io.ByteArrayOutputStream(bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n') {
                normalized.write('\n');
                index++;
            } else {
                normalized.write(bytes[index]);
            }
        }
        return normalized.toByteArray();
    }

    private static Database createV4Database() throws Exception {
        String name = "notification_receipt_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
        }
        DataSource dataSource = postgres.getDatabase("postgres", name);
        Flyway.configure().dataSource(dataSource).target("4").load().migrate();
        return new Database(name, dataSource);
    }

    private static void dropDatabase(Database database) throws Exception {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE \"" + database.name() + "\" WITH (FORCE)");
        }
    }

    private static Seed seedParents(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        long targetId = jdbc.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port,
                     status, updated_at, config_version)
                VALUES (5, ?, true, '127.0.0.1', 'receipt-db', 5432, 'UNKNOWN', ?, 1)
                RETURNING id
                """, Long.class, timestamp(created), timestamp(created));
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, activation_at, updated_at)
                VALUES (?, 1, 1, true, false, 'UP', 'FRESH', ?, ?)
                """, targetId, timestamp(created), timestamp(created));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, '[]'::jsonb, 60, 300, ?, ?)
                """, targetId, timestamp(created), timestamp(created));
        long userId = jdbc.queryForObject("""
                INSERT INTO users
                    (email, display_name, password_hash, role, enabled, auth_version,
                     created_at, updated_at)
                VALUES ('receipt@example.com', 'receipt', 'hash', 'USER', true, 1, ?, ?)
                RETURNING id
                """, Long.class, timestamp(created), timestamp(created));
        UUID sid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions
                    (sid, user_id, current_refresh_hash, created_at, expires_at, auth_version)
                VALUES (?, ?, ?, ?, ?, 1)
                """, sid, userId, "0".repeat(64), timestamp(created), timestamp(created.plusSeconds(7200)));
        long sharedRecipientId = 101L;
        jdbc.update("""
                INSERT INTO push_subscriptions
                    (id, user_id, sid, endpoint_hash, payload_key_version, payload_nonce,
                     payload_ciphertext, enabled, deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, decode(repeat('11', 32), 'hex'), 1,
                        decode(repeat('22', 12), 'hex'), decode(repeat('33', 17), 'hex'),
                        false, ?, ?, ?)
                """, sharedRecipientId, userId, sid, timestamp(created.plusSeconds(1)),
                timestamp(created), timestamp(created.plusSeconds(1)));
        jdbc.update("""
                INSERT INTO notification_webhooks
                    (id, name, provider, url_key_version, url_nonce, url_ciphertext,
                     enabled, deleted_at, created_at, updated_at)
                VALUES (?, 'receipt', 'SLACK', 1, decode(repeat('22', 12), 'hex'),
                        decode(repeat('33', 17), 'hex'), false, ?, ?, ?)
                """, sharedRecipientId, timestamp(created.plusSeconds(1)),
                timestamp(created), timestamp(created.plusSeconds(1)));
        UUID open = UUID.randomUUID();
        UUID resolved = UUID.randomUUID();
        insertIncident(jdbc, open, targetId, "OPEN", null, null, 6, created);
        insertIncident(jdbc, resolved, targetId, "RESOLVED", "RECOVERED",
                created.plusSeconds(100), 1, created);
        return new Seed(open, resolved, sharedRecipientId, sharedRecipientId);
    }

    private static void insertIncident(
            JdbcTemplate jdbc,
            UUID id,
            long targetId,
            String status,
            String resolutionReason,
            Instant resolvedAt,
            long version,
            Instant created
    ) {
        jdbc.update("""
                INSERT INTO incidents
                    (incident_id, database_config_id, database_name, rule_id, rule_type,
                     severity, status, opened_at, last_observed_at, resolved_at,
                     resolution_reason, metric_name, metric_value, threshold_value,
                     message, incident_version)
                VALUES (?, ?, 'receipt-db', 'CONNECTION_RATIO', 'CONNECTION_RATIO_EXCEEDED',
                        'WARNING', ?, ?, ?, ?, ?, 'ratio', 0.91, 0.90, 'threshold', ?)
                """, id, targetId, status, timestamp(created),
                timestamp(resolvedAt == null ? created : resolvedAt),
                resolvedAt == null ? null : timestamp(resolvedAt), resolutionReason, version);
    }

    private static void insertDelivery(
            JdbcTemplate jdbc,
            UUID incidentId,
            long version,
            String type,
            String channel,
            Long pushId,
            Long webhookId,
            String status,
            Instant created,
            Instant sentAt
    ) {
        String error = "FAILED".equals(status) ? "PROVIDER_ERROR" : null;
        Instant next = "PENDING".equals(status) ? created : null;
        jdbc.update("""
                INSERT INTO notification_deliveries
                    (incident_id, incident_version, notification_type, channel,
                     push_subscription_id, notification_webhook_id, status, attempt_count,
                     next_attempt_at, expires_at, last_error_code, created_at, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, incidentId, version, type, channel, pushId, webhookId, status,
                next == null ? null : timestamp(next), timestamp(created.plusSeconds(600)), error,
                timestamp(created), sentAt == null ? null : timestamp(sentAt));
    }

    private static void assertReceipt(
            List<Map<String, Object>> receipts,
            UUID incidentId,
            String channel,
            long recipientId,
            Instant successfulAt
    ) {
        assertThat(receipts).anySatisfy(row -> {
            assertThat(row.get("incident_id")).isEqualTo(incidentId);
            assertThat(row.get("channel")).isEqualTo(channel);
            assertThat(row.get("recipient_id")).isEqualTo(recipientId);
            assertThat(((java.sql.Timestamp) row.get("last_successful_open_or_increase_at")).toInstant())
                    .isEqualTo(successfulAt);
        });
    }

    private static void assertRejected(JdbcTemplate jdbc, String sql) {
        assertThatThrownBy(() -> jdbc.execute(sql))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Path locateRepository() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("backend/src/main/resources/db/migration"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found.");
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record Database(String name, DataSource dataSource) {
    }

    private record Seed(UUID openIncident, UUID resolvedIncident, long pushId, long webhookId) {
    }
}
