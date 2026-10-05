package com.example.monitoring.notification.api;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.session.AuthSessionSecurity;
import com.example.monitoring.notification.session.JdbcPushSubscriptionLifecycleAdapter;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.service.AuditEventService;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({EmbeddedPostgresSupport.Config.class, NotificationRecipientRepository.class,
        NotificationRecipientService.class, PartCQueryValidator.class,
        AuthSessionSecurity.class, JdbcPushSubscriptionLifecycleAdapter.class,
        NotificationRegistrationConcurrencyIntegrationTest.TestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationRegistrationConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00.000Z");
    private static final String ENDPOINT = "https://fcm.googleapis.com/push/concurrent-device";
    private static final String P256DH =
            "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
    private static final String AUTH = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final byte[] HASH = new byte[32];
    private static final EncryptedValue CIPHERTEXT =
            new EncryptedValue(1, new byte[12], new byte[17]);

    @Autowired private NotificationRecipientService service;
    @Autowired private AuthSessionSecurity sessionSecurity;
    @Autowired private JdbcPushSubscriptionLifecycleAdapter lifecycle;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private PushEndpointPolicy pushPolicy;
    @MockBean private SlackWebhookPolicy slackPolicy;
    @MockBean private NotificationSecretCodec secrets;
    @MockBean private VapidConfigurationProvider vapid;
    @MockBean private AuditEventService audits;

    @BeforeEach
    void defaults() {
        given(pushPolicy.validate(ENDPOINT)).willReturn(URI.create(ENDPOINT));
        given(secrets.endpointHash(ENDPOINT)).willReturn(HASH.clone());
        given(secrets.encryptPush(anyLong(), eq(ENDPOINT), eq(P256DH), eq(AUTH)))
                .willReturn(CIPHERTEXT);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM used_refresh_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void registrationWinnerIsSynchronouslyTombstonedByWaitingRevocation() throws Exception {
        Identity identity = identity();
        CountDownLatch encryptEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        given(secrets.encryptPush(anyLong(), eq(ENDPOINT), eq(P256DH), eq(AUTH)))
                .willAnswer(invocation -> {
                    encryptEntered.countDown();
                    assertThat(releaseRegistration.await(5, TimeUnit.SECONDS)).isTrue();
                    return CIPHERTEXT;
                });

        CompletableFuture<PushRegistration> registration = CompletableFuture.supplyAsync(
                () -> service.registerPush(identity.principal(), request()));
        assertThat(encryptEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch revocationStarted = new CountDownLatch(1);
        CompletableFuture<Void> revocation = CompletableFuture.runAsync(() -> {
            revocationStarted.countDown();
            revoke(identity);
        });
        assertThat(revocationStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(revocation).isNotDone();

        releaseRegistration.countDown();
        assertThat(registration.get(5, TimeUnit.SECONDS).created()).isTrue();
        revocation.get(5, TimeUnit.SECONDS);

        assertThat(activePushCount(identity.userId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE sid = ?",
                Boolean.class, identity.sid())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM push_subscriptions WHERE user_id = ?",
                Boolean.class, identity.userId())).isTrue();
    }

    @Test
    void revocationWinnerMakesWaitingRegistrationFailClosed() throws Exception {
        Identity identity = identity();
        CountDownLatch revokedBeforeCommit = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> revocation = CompletableFuture.runAsync(() ->
                transaction.executeWithoutResult(status -> {
                    assertThat(sessionSecurity.lockSession(identity.sid(), identity.userId())).isTrue();
                    jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE sid = ?",
                            Timestamp.from(NOW), identity.sid());
                    lifecycle.deactivateBySession(identity.sid(), NOW);
                    revokedBeforeCommit.countDown();
                    try {
                        assertThat(releaseRevocation.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }));
        assertThat(revokedBeforeCommit.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<PushRegistration> registration = CompletableFuture.supplyAsync(
                () -> service.registerPush(identity.principal(), request()));
        assertThat(registration).isNotDone();
        releaseRevocation.countDown();
        revocation.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> registration.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(com.example.monitoring.common.api.ApiException.class)
                .rootCause()
                .extracting(value -> ((com.example.monitoring.common.api.ApiException) value).getCode())
                .isEqualTo("SESSION_REVOKED");
        assertThat(activePushCount(identity.userId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE user_id = ?",
                Long.class, identity.userId())).isZero();
    }

    private void revoke(Identity identity) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThat(sessionSecurity.lockSession(identity.sid(), identity.userId())).isTrue();
            jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE sid = ?",
                    Timestamp.from(NOW), identity.sid());
            lifecycle.deactivateBySession(identity.sid(), NOW);
        });
    }

    private long activePushCount(long userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM push_subscriptions
                WHERE user_id = ? AND enabled AND deleted_at IS NULL
                """, Long.class, userId);
    }

    private PushSubscriptionRequest request() {
        return new PushSubscriptionRequest(
                ENDPOINT, NOW.plusSeconds(3600).toEpochMilli(), new PushKeys(P256DH, AUTH));
    }

    private Identity identity() {
        long userId = jdbc.queryForObject("""
                INSERT INTO users
                    (email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'concurrent', 'hash', 'USER', true, 1, ?, ?) RETURNING id
                """, Long.class, "concurrent-" + UUID.randomUUID() + "@example.com",
                Timestamp.from(NOW), Timestamp.from(NOW));
        UUID sid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions
                    (sid, user_id, current_refresh_hash, created_at, expires_at, revoked_at, auth_version)
                VALUES (?, ?, ?, ?, ?, NULL, 1)
                """, sid, userId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(7200)));
        return new Identity(userId, sid);
    }

    private record Identity(long userId, UUID sid) {
        AuthPrincipal principal() {
            return new AuthPrincipal(userId, UserRole.USER, sid, NOW.plusSeconds(7200));
        }
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        AuthService authService(AuthSessionSecurity security) {
            return new AuthService() {
                @Override
                public AuthPrincipal authenticate(String accessToken) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isSessionUsable(UUID sessionId, long userId) {
                    return security.isSessionUsable(sessionId, userId);
                }

                @Override
                public boolean lockSessionUsable(UUID sessionId, long userId) {
                    return security.lockSessionUsable(sessionId, userId);
                }
            };
        }
    }
}
