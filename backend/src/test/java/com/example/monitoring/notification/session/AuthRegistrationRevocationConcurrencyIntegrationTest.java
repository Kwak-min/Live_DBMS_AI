package com.example.monitoring.notification.session;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.dto.UpdateUserStatusRequest;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.service.AuthenticationRateLimitService;
import com.example.monitoring.auth.service.AuthenticationService;
import com.example.monitoring.auth.service.PasswordHashingService;
import com.example.monitoring.auth.service.RefreshTokenService;
import com.example.monitoring.auth.service.UserAccountService;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.persistence.PartBTransactionLocks;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.notification.api.NotificationRecipientService;
import com.example.monitoring.notification.api.PushKeys;
import com.example.monitoring.notification.api.PushRegistration;
import com.example.monitoring.notification.api.PushSubscriptionRequest;
import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
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
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({EmbeddedPostgresSupport.Config.class, NotificationRecipientRepository.class,
        NotificationRecipientService.class, PartCQueryValidator.class,
        AuthSessionSecurity.class, JdbcPushSubscriptionLifecycleAdapter.class,
        AuthenticationService.class, UserAccountService.class,
        AuthRegistrationRevocationConcurrencyIntegrationTest.TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthRegistrationRevocationConcurrencyIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00.000Z");
    private static final String ENDPOINT = "https://fcm.googleapis.com/push/concurrent-device";
    private static final String P256DH =
            "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
    private static final String AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    private static final byte[] HASH = new byte[32];
    private static final EncryptedValue CIPHERTEXT = new EncryptedValue(1, new byte[12], new byte[17]);

    @Autowired private NotificationRecipientService recipients;
    @Autowired private AuthenticationService authentication;
    @Autowired private UserAccountService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private PushEndpointPolicy pushPolicy;
    @MockBean private SlackWebhookPolicy slackPolicy;
    @MockBean private NotificationSecretCodec secrets;
    @MockBean private VapidConfigurationProvider vapid;
    @MockBean private AuditEventService audits;
    @MockBean private RefreshTokenService refreshTokens;
    @MockBean private AccessTokenService accessTokens;
    @MockBean private AuthenticationRateLimitService rateLimits;
    @MockBean private PasswordHashingService passwords;
    @MockBean private PartBTransactionLocks transactionLocks;

    private ExecutorService workers;

    @BeforeEach
    void defaults() {
        workers = Executors.newFixedThreadPool(2);
        given(pushPolicy.validate(ENDPOINT)).willReturn(URI.create(ENDPOINT));
        given(secrets.endpointHash(ENDPOINT)).willReturn(HASH.clone());
        given(secrets.encryptPush(anyLong(), eq(ENDPOINT), eq(P256DH), eq(AUTH))).willReturn(CIPHERTEXT);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (registrationRelease != null) {
            registrationRelease.countDown();
        }
        workers.shutdownNow();
        assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM used_refresh_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users WHERE email LIKE 'registration-race-%@example.test'");
    }

    @Test
    void registrationWinnerIsTombstonedByTheRealLogoutEntrypoint() throws Exception {
        Identity identity = identity();
        when(refreshTokens.hash("logout-token")).thenReturn(identity.refreshHash());
        CountDownLatch encryptionEntered = holdRegistrationOpen();
        CountDownLatch releaseRegistration = registrationRelease;

        CompletableFuture<PushRegistration> registration = CompletableFuture.supplyAsync(
                () -> recipients.registerPush(identity.principal(), request()), workers);
        assertThat(encryptionEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch logoutStarted = new CountDownLatch(1);
        CompletableFuture<Void> logout = CompletableFuture.runAsync(() -> {
            logoutStarted.countDown();
            authentication.logout("logout-token");
        }, workers);
        assertThat(logoutStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(logout).isNotDone();
        releaseRegistration.countDown();

        assertThat(registration.get(5, TimeUnit.SECONDS).created()).isTrue();
        logout.get(5, TimeUnit.SECONDS);
        assertRevokedWithNoActivePush(identity, true);
        assertThat(pushCount(identity.userId())).isOne();
    }

    @Test
    void logoutWinnerMakesRegistrationFailClosed() throws Exception {
        Identity identity = identity();
        when(refreshTokens.hash("logout-token")).thenReturn(identity.refreshHash());
        CountDownLatch logoutApplied = new CountDownLatch(1);
        CountDownLatch releaseLogout = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> logout = CompletableFuture.runAsync(() ->
                transaction.executeWithoutResult(status -> {
                    authentication.logout("logout-token");
                    logoutApplied.countDown();
                    await(releaseLogout);
                }), workers);
        assertThat(logoutApplied.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch registrationStarted = new CountDownLatch(1);
        CompletableFuture<PushRegistration> registration = CompletableFuture.supplyAsync(() -> {
            registrationStarted.countDown();
            return recipients.registerPush(identity.principal(), request());
        }, workers);
        assertThat(registrationStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registration).isNotDone();
        releaseLogout.countDown();
        logout.get(5, TimeUnit.SECONDS);

        assertSessionRevoked(registration);
        assertRevokedWithNoActivePush(identity, true);
        assertThat(pushCount(identity.userId())).isZero();
    }

    @Test
    void registrationWinnerIsTombstonedByTheRealDisableEntrypoint() throws Exception {
        Identity identity = identity();
        CountDownLatch encryptionEntered = holdRegistrationOpen();
        CountDownLatch releaseRegistration = registrationRelease;

        CompletableFuture<PushRegistration> registration = CompletableFuture.supplyAsync(
                () -> recipients.registerPush(identity.principal(), request()), workers);
        assertThat(encryptionEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch disableStarted = new CountDownLatch(1);
        CompletableFuture<Void> disable = CompletableFuture.runAsync(() -> {
            disableStarted.countDown();
            users.updateStatus(identity.userId(), new UpdateUserStatusRequest(false));
        }, workers);
        assertThat(disableStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(disable).isNotDone();
        releaseRegistration.countDown();

        assertThat(registration.get(5, TimeUnit.SECONDS).created()).isTrue();
        disable.get(5, TimeUnit.SECONDS);
        assertRevokedWithNoActivePush(identity, false);
        assertThat(pushCount(identity.userId())).isOne();
    }

    @Test
    void disableWinnerMakesRegistrationFailClosed() throws Exception {
        Identity identity = identity();
        CountDownLatch disableApplied = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> disable = CompletableFuture.runAsync(() ->
                transaction.executeWithoutResult(status -> {
                    users.updateStatus(identity.userId(), new UpdateUserStatusRequest(false));
                    disableApplied.countDown();
                    await(releaseDisable);
                }), workers);
        assertThat(disableApplied.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch registrationStarted = new CountDownLatch(1);
        CompletableFuture<PushRegistration> registration = CompletableFuture.supplyAsync(() -> {
            registrationStarted.countDown();
            return recipients.registerPush(identity.principal(), request());
        }, workers);
        assertThat(registrationStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registration).isNotDone();
        releaseDisable.countDown();
        disable.get(5, TimeUnit.SECONDS);

        assertSessionRevoked(registration);
        assertRevokedWithNoActivePush(identity, false);
        assertThat(pushCount(identity.userId())).isZero();
    }

    private CountDownLatch registrationRelease;

    private CountDownLatch holdRegistrationOpen() {
        CountDownLatch entered = new CountDownLatch(1);
        registrationRelease = new CountDownLatch(1);
        given(secrets.encryptPush(anyLong(), eq(ENDPOINT), eq(P256DH), eq(AUTH))).willAnswer(invocation -> {
            entered.countDown();
            assertThat(registrationRelease.await(5, TimeUnit.SECONDS)).isTrue();
            return CIPHERTEXT;
        });
        return entered;
    }

    private void assertSessionRevoked(CompletableFuture<PushRegistration> registration) {
        assertThatThrownBy(() -> registration.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApiException.class)
                .rootCause()
                .extracting(value -> ((ApiException) value).getCode())
                .isEqualTo("SESSION_REVOKED");
    }

    private void assertRevokedWithNoActivePush(Identity identity, boolean expectedEnabled) {
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE sid = ?",
                Boolean.class, identity.sid())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM users WHERE id = ?", Boolean.class, identity.userId()))
                .isEqualTo(expectedEnabled);
        assertThat(activePushCount(identity.userId())).isZero();
    }

    private long activePushCount(long userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM push_subscriptions
                WHERE user_id = ? AND enabled AND deleted_at IS NULL
                """, Long.class, userId);
    }

    private long pushCount(long userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM push_subscriptions WHERE user_id = ?", Long.class, userId);
    }

    private PushSubscriptionRequest request() {
        return new PushSubscriptionRequest(
                ENDPOINT, NOW.plusSeconds(3_600).toEpochMilli(), new PushKeys(P256DH, AUTH));
    }

    private Identity identity() {
        long userId = jdbc.queryForObject("""
                INSERT INTO users
                    (email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'concurrent', 'hash', 'USER', true, 1, ?, ?) RETURNING id
                """, Long.class, "registration-race-" + UUID.randomUUID() + "@example.test",
                Timestamp.from(NOW), Timestamp.from(NOW));
        UUID sid = UUID.randomUUID();
        String refreshHash = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        jdbc.update("""
                INSERT INTO auth_sessions
                    (sid, user_id, current_refresh_hash, created_at, expires_at, revoked_at, auth_version)
                VALUES (?, ?, ?, ?, ?, NULL, 1)
                """, sid, userId, refreshHash, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(7_200)));
        return new Identity(userId, sid, refreshHash);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Identity(long userId, UUID sid, String refreshHash) {
        AuthPrincipal principal() {
            return new AuthPrincipal(userId, UserRole.USER, sid, NOW.plusSeconds(7_200));
        }

    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
