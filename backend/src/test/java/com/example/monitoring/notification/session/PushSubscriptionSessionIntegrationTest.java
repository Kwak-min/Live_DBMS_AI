package com.example.monitoring.notification.session;

import com.example.monitoring.audit.service.PartBRetentionService;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmbeddedPostgresSupport.Config.class, PushSubscriptionSessionIntegrationTest.TimeConfig.class,
        AuthSessionSecurity.class, JdbcPushSubscriptionLifecycleAdapter.class, PartBRetentionService.class})
class PushSubscriptionSessionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthSessionSecurity sessionSecurity;
    @Autowired private PushSubscriptionLifecyclePort lifecyclePort;
    @Autowired private PartBRetentionService retentionService;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_push_tombstone ON push_subscriptions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_push_tombstone()");
        jdbcTemplate.update("""
                DELETE FROM push_subscriptions
                 WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'session-%@example.test')
                """);
        jdbcTemplate.update("""
                DELETE FROM auth_sessions
                 WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'session-%@example.test')
                """);
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'session-%@example.test'");
    }

    @Test
    void sharedValidationRequiresExactUserSessionEnabledVersionAndExpiryState() {
        Fixture fixture = fixture(NOW.plusSeconds(3600));

        assertThat(sessionSecurity.isSessionUsable(fixture.sessionId(), fixture.userId())).isTrue();
        assertThat(sessionSecurity.isSessionUsable(fixture.sessionId(), fixture.userId() + 1)).isFalse();

        jdbcTemplate.update("UPDATE users SET auth_version = auth_version + 1 WHERE id = ?", fixture.userId());
        assertThat(sessionSecurity.isSessionUsable(fixture.sessionId(), fixture.userId())).isFalse();
        jdbcTemplate.update("UPDATE users SET auth_version = 1, enabled = FALSE WHERE id = ?", fixture.userId());
        assertThat(sessionSecurity.isSessionUsable(fixture.sessionId(), fixture.userId())).isFalse();
    }

    @Test
    void sessionUserAndRetentionTombstonesAreSynchronous() {
        Fixture sessionFixture = fixture(NOW.plusSeconds(3600));
        assertThat(lifecyclePort.deactivateBySession(sessionFixture.sessionId(), NOW)).isEqualTo(1);
        assertTombstoned(sessionFixture.subscriptionId());

        Fixture userFixture = fixture(NOW.plusSeconds(3600));
        assertThat(lifecyclePort.deactivateByUser(userFixture.userId(), NOW)).isEqualTo(1);
        assertTombstoned(userFixture.subscriptionId());

        Fixture retained = fixture(NOW.minusSeconds(3 * 86_400));
        PartBRetentionService.CleanupResult result = retentionService.purge(NOW);
        assertThat(result.sessions()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM push_subscriptions WHERE id = ?", Boolean.class, retained.subscriptionId()))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sid IS NULL FROM push_subscriptions WHERE id = ?", Boolean.class, retained.subscriptionId()))
                .isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedPushTombstoneRollsBackTheAuthRevocation() {
        Fixture fixture = fixture(NOW.plusSeconds(3600));
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_push_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced tombstone failure'; END $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_push_tombstone BEFORE UPDATE ON push_subscriptions
                FOR EACH ROW EXECUTE FUNCTION fail_push_tombstone()
                """);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            jdbcTemplate.update("UPDATE auth_sessions SET revoked_at = ? WHERE sid = ?",
                    java.sql.Timestamp.from(NOW), fixture.sessionId());
            lifecyclePort.deactivateBySession(fixture.sessionId(), NOW);
        })).hasMessageContaining("forced tombstone failure");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NULL FROM auth_sessions WHERE sid = ?", Boolean.class, fixture.sessionId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM push_subscriptions WHERE id = ?", Boolean.class, fixture.subscriptionId()))
                .isTrue();
    }

    private Fixture fixture(Instant expiresAt) {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String email = "session-" + suffix + "@example.test";
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users(email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'session test', 'hash', 'USER', TRUE, 1, ?, ?) RETURNING id
                """, Long.class, email, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO auth_sessions(sid, user_id, current_refresh_hash, created_at, expires_at, auth_version)
                VALUES (?, ?, ?, ?, ?, 1)
                """, sessionId, userId, UUID.randomUUID().toString().replace("-", ""),
                java.sql.Timestamp.from(NOW.minusSeconds(60)), java.sql.Timestamp.from(expiresAt));
        Long subscriptionId = jdbcTemplate.queryForObject("SELECT nextval('push_subscriptions_id_seq')", Long.class);
        byte[] endpointHash = new byte[32];
        java.nio.ByteBuffer.wrap(endpointHash).putLong(subscriptionId);
        jdbcTemplate.update("""
                INSERT INTO push_subscriptions(id, user_id, sid, endpoint_hash, payload_key_version,
                                               payload_nonce, payload_ciphertext, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, TRUE, ?, ?)
                """, subscriptionId, userId, sessionId, endpointHash, new byte[12], new byte[17],
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return new Fixture(userId, sessionId, subscriptionId);
    }

    private void assertTombstoned(long subscriptionId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT enabled, deleted_at, updated_at FROM push_subscriptions WHERE id = ?", subscriptionId);
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("updated_at")).isEqualTo(row.get("deleted_at"));
    }

    private record Fixture(long userId, UUID sessionId, long subscriptionId) { }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
