package com.example.monitoring.notification.session;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.dto.UpdateUserRoleRequest;
import com.example.monitoring.auth.dto.UpdateUserStatusRequest;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.auth.service.AuthenticationRateLimitService;
import com.example.monitoring.auth.service.AuthenticationService;
import com.example.monitoring.auth.service.PasswordHashingService;
import com.example.monitoring.auth.service.RefreshTokenService;
import com.example.monitoring.auth.service.UserAccountService;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.persistence.PartBTransactionLocks;
import com.example.monitoring.service.AuditEventService;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmbeddedPostgresSupport.Config.class, AuthNotificationRevocationIntegrationTest.TimeConfig.class,
        AuthSessionSecurity.class, JdbcPushSubscriptionLifecycleAdapter.class,
        AuthenticationService.class, UserAccountService.class})
class AuthNotificationRevocationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00.123Z");
    private static final String CURRENT_HASH = "a".repeat(64);
    private static final String USED_HASH = "b".repeat(64);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuthenticationService authenticationService;
    @Autowired private UserAccountService userAccountService;
    @PersistenceContext private EntityManager entityManager;

    @MockBean private RefreshTokenService refreshTokens;
    @MockBean private AccessTokenService accessTokens;
    @MockBean private AuthenticationRateLimitService rateLimits;
    @MockBean private PasswordHashingService passwords;
    @MockBean private AuditEventService auditEvents;
    @MockBean private PartBTransactionLocks transactionLocks;

    @AfterEach
    void cleanupCommittedFixture() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_push_tombstone ON push_subscriptions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_push_tombstone()");
        jdbcTemplate.update("""
                DELETE FROM used_refresh_tokens
                 WHERE sid IN (SELECT sid FROM auth_sessions
                                WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'entrypoint-%@example.test'))
                """);
        jdbcTemplate.update("""
                DELETE FROM push_subscriptions
                 WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'entrypoint-%@example.test')
                """);
        jdbcTemplate.update("""
                DELETE FROM auth_sessions
                 WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'entrypoint-%@example.test')
                """);
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'entrypoint-%@example.test'");
    }

    @Test
    void logoutEntrypointRevokesSessionAndTombstonesPushAtTheSameInstant() {
        Fixture fixture = fixture(CURRENT_HASH, NOW.plusSeconds(3_600));
        when(refreshTokens.hash("logout-token")).thenReturn(CURRENT_HASH);

        authenticationService.logout("logout-token");

        assertRevokedAndTombstoned(fixture);
    }

    @Test
    void expiryAndRefreshReuseEntrypointsSynchronouslyTombstonePushes() {
        Fixture expired = fixture(CURRENT_HASH, NOW.minusMillis(1));
        when(refreshTokens.hash("expired-token")).thenReturn(CURRENT_HASH);

        assertThatThrownBy(() -> authenticationService.refresh("expired-token"))
                .isInstanceOf(ApiException.class);
        assertRevokedAndTombstoned(expired);

        Fixture reused = fixture("c".repeat(64), NOW.plusSeconds(3_600));
        jdbcTemplate.update("""
                INSERT INTO used_refresh_tokens(token_hash, sid, used_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, USED_HASH, reused.sessionId(), Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.plusSeconds(3_600)));
        when(refreshTokens.hash("reused-token")).thenReturn(USED_HASH);

        assertThatThrownBy(() -> authenticationService.refresh("reused-token"))
                .isInstanceOf(ApiException.class);
        assertRevokedAndTombstoned(reused);
    }

    @Test
    void roleAndStatusEntrypointsRevokeSessionsBeforeTombstoningPushes() {
        Fixture roleChanged = fixture(CURRENT_HASH, NOW.plusSeconds(3_600));
        userAccountService.updateRole(roleChanged.userId(), new UpdateUserRoleRequest(UserRole.ADMIN));
        assertRevokedAndTombstoned(roleChanged);

        Fixture disabled = fixture("d".repeat(64), NOW.plusSeconds(3_600));
        userAccountService.updateStatus(disabled.userId(), new UpdateUserStatusRequest(false));
        assertRevokedAndTombstoned(disabled);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tombstoneFailureRollsBackTheRealLogoutEntrypoint() {
        Fixture fixture = fixture(CURRENT_HASH, NOW.plusSeconds(3_600));
        when(refreshTokens.hash("logout-token")).thenReturn(CURRENT_HASH);
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_push_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced tombstone failure'; END $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_push_tombstone BEFORE UPDATE ON push_subscriptions
                FOR EACH ROW EXECUTE FUNCTION fail_push_tombstone()
                """);

        assertThatThrownBy(() -> authenticationService.logout("logout-token"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("forced tombstone failure");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NULL FROM auth_sessions WHERE sid = ?", Boolean.class, fixture.sessionId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM push_subscriptions WHERE id = ?", Boolean.class, fixture.subscriptionId()))
                .isTrue();
    }

    private Fixture fixture(String refreshHash, Instant expiresAt) {
        String email = "entrypoint-" + UUID.randomUUID() + "@example.test";
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users(email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'entrypoint test', 'hash', 'USER', TRUE, 1, ?, ?) RETURNING id
                """, Long.class, email, Timestamp.from(NOW), Timestamp.from(NOW));
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO auth_sessions(sid, user_id, current_refresh_hash, created_at, expires_at, auth_version)
                VALUES (?, ?, ?, ?, ?, 1)
                """, sessionId, userId, refreshHash, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(expiresAt));
        Long subscriptionId = jdbcTemplate.queryForObject("SELECT nextval('push_subscriptions_id_seq')", Long.class);
        byte[] endpointHash = new byte[32];
        ByteBuffer.wrap(endpointHash).putLong(subscriptionId);
        jdbcTemplate.update("""
                INSERT INTO push_subscriptions(id, user_id, sid, endpoint_hash, payload_key_version,
                                               payload_nonce, payload_ciphertext, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, TRUE, ?, ?)
                """, subscriptionId, userId, sessionId, endpointHash, new byte[12], new byte[17],
                Timestamp.from(NOW), Timestamp.from(NOW));
        return new Fixture(userId, sessionId, subscriptionId);
    }

    private void assertRevokedAndTombstoned(Fixture fixture) {
        entityManager.flush();
        Timestamp revokedAt = jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM auth_sessions WHERE sid = ?", Timestamp.class, fixture.sessionId());
        Timestamp deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM push_subscriptions WHERE id = ?", Timestamp.class, fixture.subscriptionId());
        Timestamp updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM push_subscriptions WHERE id = ?", Timestamp.class, fixture.subscriptionId());
        assertThat(revokedAt).isEqualTo(Timestamp.from(NOW));
        assertThat(deletedAt).isEqualTo(Timestamp.from(NOW));
        assertThat(updatedAt).isEqualTo(deletedAt);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT enabled FROM push_subscriptions WHERE id = ?", Boolean.class, fixture.subscriptionId()))
                .isFalse();
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
