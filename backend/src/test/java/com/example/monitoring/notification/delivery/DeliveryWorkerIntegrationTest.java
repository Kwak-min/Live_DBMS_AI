package com.example.monitoring.notification.delivery;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.common.outbox.ProcessedEventStore;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PushSecretBundle;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.slack.SlackMessage;
import com.example.monitoring.notification.slack.SlackSender;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository;
import com.example.monitoring.notification.session.AuthSessionSecurity;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore;
import com.example.monitoring.notification.scheduling.NotificationSchedulingTransaction;
import com.example.monitoring.notification.stream.NotificationIncidentEvent;
import com.example.monitoring.notification.transport.DeliveryOutcome;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.retention.PartCRetentionService;
import com.example.monitoring.retention.PartCRetentionStore;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.notification.webpush.WebPushSender;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({NotificationDeliveryStore.class, NotificationDeliveryTransaction.class,
        NotificationRecipientRepository.class,
        AuthSessionSecurity.class,
        ProcessedEventStore.class,
        NotificationSchedulingStore.class, NotificationSchedulingTransaction.class,
        PartCRetentionStore.class, PartCRetentionService.class,
        DeliveryWorkerIntegrationTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliveryWorkerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00.000Z");
    private static final String ENDPOINT = "https://push.example.test/subscription/one";
    private static final String P256DH =
            "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
    private static final String AUTH_KEY = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    private static final String SLACK_URL = "https://hooks.slack.com/services/T000/B000/shared";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private NotificationDeliveryTransaction deliveryTransaction;
    @Autowired private NotificationSchedulingTransaction schedulingTransaction;
    @Autowired private PartCRetentionService retentionService;
    @Autowired private NotificationRecipientRepository recipients;
    @Autowired private AuthSessionSecurity sessionSecurity;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableClock clock;
    private final SlackWebhookPolicy slackPolicy = new SlackWebhookPolicy();
    private final List<PostgresDeliveryLease> leases = new CopyOnWriteArrayList<>();
    @MockBean private AuthService authService;
    @MockBean private NotificationSecretCodec secrets;
    @MockBean private WebPushSender webPushSender;
    @MockBean private SlackSender slackSender;

    @BeforeEach
    void defaults() {
        clock.set(NOW);
        given(secrets.decryptPush(anyLong(), anyInt(), any(byte[].class), any(byte[].class)))
                .willReturn(new PushSecretBundle(ENDPOINT, P256DH, AUTH_KEY));
        given(secrets.decryptSlack(anyLong(), anyInt(), any(byte[].class), any(byte[].class)))
                .willReturn(SLACK_URL);
    }

    @AfterEach
    void cleanup() {
        leases.forEach(PostgresDeliveryLease::close);
        leases.clear();
        jdbc.execute("DROP TRIGGER IF EXISTS fail_sent_completion ON notification_deliveries");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_sent_completion()");
        jdbc.update("DELETE FROM processed_events WHERE consumer_group = ?",
                NotificationSchedulingTransaction.CONSUMER_GROUP);
        jdbc.update("DELETE FROM notification_success_receipts");
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM used_refresh_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM risk_policies");
        jdbc.update("DELETE FROM monitoring_states");
        jdbc.update("DELETE FROM database_configs");
        reset(authService, secrets, webPushSender, slackSender);
    }

    @Test
    void twoWorkersUseOneProcessLease() throws Exception {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        given(webPushSender.send(any(), any())).willAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            enteredProvider.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return DeliveryOutcome.of(DeliveryOutcomeKind.SENT);
        });
        PostgresDeliveryLease firstLease = lease();
        PostgresDeliveryLease secondLease = lease();
        NotificationDeliveryWorker first = worker(firstLease, new SlackAttemptPacer());
        NotificationDeliveryWorker second = worker(secondLease, new SlackAttemptPacer());

        CompletableFuture<Integer> firstRun = CompletableFuture.supplyAsync(first::runOnce);
        assertThat(enteredProvider.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.runOnce()).isZero();
        releaseProvider.countDown();

        assertThat(firstRun.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(transactionActive.get()).isFalse();
        assertDelivery(fixture.deliveryId(), "SENT", 1, null, NOW.plusSeconds(600));
        verify(webPushSender).send(any(), any());
    }

    @Test
    void retriesRemainInOriginalWindow() {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        given(webPushSender.send(any(), any()))
                .willReturn(DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR));
        NotificationDeliveryWorker worker = worker(lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "PENDING", 1, NOW.plusSeconds(5), NOW.plusSeconds(600));

        clock.set(NOW.plusSeconds(5));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "PENDING", 2, NOW.plusSeconds(30), NOW.plusSeconds(600));

        clock.set(NOW.plusSeconds(30));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "PENDING", 3, NOW.plusSeconds(120), NOW.plusSeconds(600));

        clock.set(NOW.plusSeconds(120));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "FAILED", 4, null, NOW.plusSeconds(600));
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notification_deliveries WHERE id = ?",
                String.class, fixture.deliveryId())).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void retryAfterUsesFallbackForZeroAndCancelsWhenDelayExceedsWindow() {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        given(webPushSender.send(any(), any()))
                .willReturn(DeliveryOutcome.rateLimited(Duration.ZERO),
                        DeliveryOutcome.rateLimited(Duration.ofSeconds(Long.MAX_VALUE)));
        NotificationDeliveryWorker worker = worker(lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "PENDING", 1, NOW.plusSeconds(5), NOW.plusSeconds(600));

        clock.set(NOW.plusSeconds(5));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "CANCELLED", 2, null, NOW.plusSeconds(600));
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notification_deliveries WHERE id = ?",
                String.class, fixture.deliveryId())).isEqualTo("RATE_LIMITED");
    }

    @Test
    void recipientGoneTombstonesRecipientAndCompletesCurrentDelivery() {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        given(webPushSender.send(any(), any()))
                .willReturn(DeliveryOutcome.of(DeliveryOutcomeKind.RECIPIENT_GONE));
        NotificationDeliveryWorker worker = worker(lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isEqualTo(1);

        assertDelivery(fixture.deliveryId(), "FAILED", 1, null, NOW.plusSeconds(600));
        assertThat(jdbc.queryForMap(
                "SELECT enabled, deleted_at IS NOT NULL AS deleted FROM push_subscriptions WHERE id = ?",
                fixture.recipientId()))
                .containsEntry("enabled", false)
                .containsEntry("deleted", true);
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notification_deliveries WHERE id = ?",
                String.class, fixture.deliveryId())).isEqualTo("RECIPIENT_GONE");
    }

    @Test
    void recipientGoneFromOldPushSnapshotDoesNotTombstoneRefreshedSubscription() throws Exception {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        given(webPushSender.send(any(), any())).willAnswer(invocation -> {
            enteredProvider.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return DeliveryOutcome.of(DeliveryOutcomeKind.RECIPIENT_GONE);
        });

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(
                worker(lease(), new SlackAttemptPacer())::runOnce);
        assertThat(enteredProvider.await(5, TimeUnit.SECONDS)).isTrue();
        jdbc.update("""
                UPDATE push_subscriptions
                SET payload_key_version = 2,
                    payload_nonce = decode(repeat('22', 12), 'hex'),
                    payload_ciphertext = decode(repeat('33', 17), 'hex'),
                    updated_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW.plusMillis(1)), fixture.recipientId());
        releaseProvider.countDown();

        assertThat(run.get(5, TimeUnit.SECONDS)).isOne();
        assertDelivery(fixture.deliveryId(), "FAILED", 1, null, NOW.plusSeconds(600));
        assertThat(jdbc.queryForMap("""
                SELECT enabled, deleted_at, payload_key_version
                FROM push_subscriptions WHERE id = ?
                """, fixture.recipientId()))
                .containsEntry("enabled", true)
                .containsEntry("deleted_at", null)
                .containsEntry("payload_key_version", 2);
    }

    @Test
    void recipientGoneFromOldWebhookSnapshotDoesNotTombstoneUpdatedUrl() throws Exception {
        Fixture fixture = slackFixture(31);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        given(slackSender.send(any(), any())).willAnswer(invocation -> {
            enteredProvider.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return DeliveryOutcome.of(DeliveryOutcomeKind.RECIPIENT_GONE);
        });

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(
                worker(lease(), new SlackAttemptPacer())::runOnce);
        assertThat(enteredProvider.await(7, TimeUnit.SECONDS)).isTrue();
        jdbc.update("""
                UPDATE notification_webhooks
                SET url_key_version = 2,
                    url_nonce = decode(repeat('22', 12), 'hex'),
                    url_ciphertext = decode(repeat('33', 17), 'hex'),
                    updated_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW.plusMillis(1)), fixture.recipientId());
        releaseProvider.countDown();

        assertThat(run.get(5, TimeUnit.SECONDS)).isOne();
        assertDelivery(fixture.deliveryId(), "FAILED", 1, null, NOW.plusSeconds(600));
        assertThat(jdbc.queryForMap("""
                SELECT enabled, deleted_at, url_key_version
                FROM notification_webhooks WHERE id = ?
                """, fixture.recipientId()))
                .containsEntry("enabled", true)
                .containsEntry("deleted_at", null)
                .containsEntry("url_key_version", 2);
    }

    @Test
    void expiredUnclaimedWorkIsCancelledWithoutProviderIo() {
        Fixture fixture = pushFixture();
        jdbc.update("""
                UPDATE notification_deliveries
                SET created_at = ?, next_attempt_at = ?, expires_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW.minusSeconds(601)), Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(1)), fixture.deliveryId());
        NotificationDeliveryWorker worker = worker(lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isZero();

        assertDelivery(fixture.deliveryId(), "CANCELLED", 0, null, NOW.minusSeconds(1));
        verify(webPushSender, never()).send(any(), any());
    }

    @Test
    void attemptedWorkExpiredDuringRestartIsCancelledAndPreservesLastError() {
        Fixture fixture = pushFixture();
        jdbc.update("""
                UPDATE notification_deliveries
                SET created_at = ?, attempt_count = 2, last_error_code = 'PROVIDER_ERROR',
                    next_attempt_at = ?, expires_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW.minusSeconds(601)), Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(1)), fixture.deliveryId());
        NotificationDeliveryWorker worker = worker(lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isZero();

        assertDelivery(fixture.deliveryId(), "CANCELLED", 2, null, NOW.minusSeconds(1));
        assertThat(jdbc.queryForObject(
                "SELECT last_error_code FROM notification_deliveries WHERE id = ?",
                String.class, fixture.deliveryId())).isEqualTo("PROVIDER_ERROR");
        verify(webPushSender, never()).send(any(), any());
    }

    @Test
    void stopsOnLeaseConnectionLoss() throws Exception {
        Fixture fixture = slackFixture(1);
        given(slackSender.send(eq(SLACK_URL), any()))
                .willReturn(DeliveryOutcome.of(DeliveryOutcomeKind.SENT));
        PostgresDeliveryLease lostLease = lease();
        NotificationDeliveryWorker first = worker(lostLease, new SlackAttemptPacer());
        int baselineActiveConnections = dataSource.getHikariPoolMXBean().getActiveConnections();

        CompletableFuture<Integer> firstRun = CompletableFuture.supplyAsync(first::runOnce);
        await().atMost(Duration.ofSeconds(3)).until(() -> lostLease.backendPid() > 0);
        assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class,
                lostLease.backendPid())).isTrue();

        assertThat(firstRun.get(5, TimeUnit.SECONDS)).isZero();
        verify(slackSender, never()).send(any(), any());
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(dataSource.getHikariPoolMXBean().getActiveConnections())
                        .isEqualTo(baselineActiveConnections));

        Timestamp due = jdbc.queryForObject(
                "SELECT next_attempt_at FROM notification_deliveries WHERE id = ?",
                Timestamp.class, fixture.deliveryId());
        clock.set(due == null ? NOW : due.toInstant());
        NotificationDeliveryWorker replacement = worker(
                lease(), new SlackAttemptPacer());
        assertThat(replacement.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "SENT",
                jdbc.queryForObject("SELECT attempt_count FROM notification_deliveries WHERE id = ?",
                        Integer.class, fixture.deliveryId()), null, NOW.plusSeconds(600));
        verify(slackSender).send(eq(SLACK_URL), any());
    }

    @Test
    void knownSuccessAfterLeaseLossPersistsReceiptAndStopsFurtherSends() throws Exception {
        Fixture first = pushFixture();
        additionalSlackDelivery(first.incidentId(), 32);
        given(authService.isSessionUsable(first.sid(), first.userId())).willReturn(true);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        given(webPushSender.send(any(), any())).willAnswer(invocation -> {
            enteredProvider.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return DeliveryOutcome.of(DeliveryOutcomeKind.SENT);
        });
        PostgresDeliveryLease lostLease = lease();
        NotificationDeliveryWorker worker = worker(lostLease, new SlackAttemptPacer());

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(worker::runOnce);
        assertThat(enteredProvider.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class,
                lostLease.backendPid())).isTrue();
        releaseProvider.countDown();

        assertThat(run.get(5, TimeUnit.SECONDS)).isOne();
        assertDelivery(first.deliveryId(), "SENT", 1, null, NOW.plusSeconds(600));
        assertReceipt(first.incidentId(), "WEB_PUSH", first.recipientId(), NOW);
        verify(slackSender, never()).send(any(), any());
    }

    @Test
    void revokedSessionCancelsPush() {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(false);
        NotificationDeliveryWorker worker = worker(
                lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isZero();

        assertDelivery(fixture.deliveryId(), "CANCELLED", 1, null, NOW.plusSeconds(600));
        verify(secrets, never()).decryptPush(anyLong(), anyInt(), any(), any());
        verify(webPushSender, never()).send(any(), any());
    }

    @Test
    void committedRevocationCancelsClaimWithoutHttp() {
        Fixture fixture = pushFixture();
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE sid = ?",
                Timestamp.from(NOW), fixture.sid());
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willAnswer(invocation ->
                sessionSecurity.isSessionUsable(fixture.sid(), fixture.userId()));
        NotificationDeliveryWorker worker = worker(lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isZero();

        assertDelivery(fixture.deliveryId(), "CANCELLED", 1, null, NOW.plusSeconds(600));
        verify(webPushSender, never()).send(any(), any());
    }

    @Test
    void slackFailuresPaceCanonicalUrlAcrossRecipientIdsSlowSenderAndRestartHandoff() {
        Fixture first = slackFixture(11);
        Fixture second = additionalSlackDelivery(first.incidentId(), 12);
        given(secrets.decryptSlack(eq(first.recipientId()), anyInt(), any(byte[].class), any(byte[].class)))
                .willReturn(SLACK_URL);
        given(secrets.decryptSlack(eq(second.recipientId()), anyInt(), any(byte[].class), any(byte[].class)))
                .willReturn("HTTPS://HOOKS.SLACK.COM/services/T000/B000/shared");
        List<Long> attempts = new CopyOnWriteArrayList<>();
        List<Long> completions = new CopyOnWriteArrayList<>();
        given(slackSender.send(any(), any())).willAnswer(invocation -> {
            int attemptNumber = attempts.size();
            attempts.add(System.nanoTime());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (attemptNumber == 0) {
                Thread.sleep(1_100);
            }
            completions.add(System.nanoTime());
            return DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR);
        });
        PostgresDeliveryLease firstLease = lease();
        NotificationDeliveryWorker firstWorker = worker(firstLease, new SlackAttemptPacer());

        long firstLeaseStart = System.nanoTime();
        assertThat(firstWorker.runOnce()).isEqualTo(2);
        assertThat(Duration.ofNanos(attempts.get(0) - firstLeaseStart)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(Duration.ofNanos(attempts.get(1) - completions.get(0)))
                .isGreaterThanOrEqualTo(Duration.ofMillis(900));

        clock.set(NOW.plusSeconds(5));
        firstLease.close();
        NotificationDeliveryWorker restarted = worker(
                lease(), new SlackAttemptPacer());
        long restartStart = System.nanoTime();
        assertThat(restarted.runOnce()).isEqualTo(2);
        assertThat(Duration.ofNanos(attempts.get(2) - restartStart)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(Duration.ofNanos(attempts.get(3) - completions.get(2)))
                .isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertDelivery(first.deliveryId(), "PENDING", 2, NOW.plusSeconds(30), NOW.plusSeconds(600));
        assertDelivery(second.deliveryId(), "PENDING", 2, NOW.plusSeconds(30), NOW.plusSeconds(600));
    }

    @Test
    void lostSuccessResponseMayDuplicateExternallyButKeepsDurableAttemptsBounded() {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        AtomicInteger externalAccepts = new AtomicInteger();
        given(webPushSender.send(any(), any())).willAnswer(invocation -> {
            int attempt = externalAccepts.incrementAndGet();
            return DeliveryOutcome.of(attempt == 1
                    ? DeliveryOutcomeKind.TIMEOUT : DeliveryOutcomeKind.SENT);
        });
        NotificationDeliveryWorker worker = worker(
                lease(), new SlackAttemptPacer());

        assertThat(worker.runOnce()).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "PENDING", 1, NOW.plusSeconds(5), NOW.plusSeconds(600));
        clock.set(NOW.plusSeconds(5));
        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(externalAccepts).hasValue(2);
        assertDelivery(fixture.deliveryId(), "SENT", 2, null, NOW.plusSeconds(600));
        assertReceipt(fixture.incidentId(), "WEB_PUSH", fixture.recipientId(), NOW.plusSeconds(5));
    }

    @Test
    void observationOnlyIncidentTouchDoesNotChangeEscalationOccurrenceTime() {
        Fixture fixture = slackFixture(21);
        jdbc.update("""
                UPDATE notification_deliveries
                SET notification_type = 'SEVERITY_INCREASED'
                WHERE id = ?
                """, fixture.deliveryId());
        jdbc.update("""
                UPDATE incidents
                SET last_observed_at = ?
                WHERE incident_id = ?
                """, Timestamp.from(NOW.plusSeconds(300)), fixture.incidentId());
        AtomicReference<SlackMessage> sent = new AtomicReference<>();
        given(slackSender.send(eq(SLACK_URL), any())).willAnswer(invocation -> {
            sent.set(invocation.getArgument(1));
            return DeliveryOutcome.of(DeliveryOutcomeKind.SENT);
        });

        assertThat(worker(lease(), new SlackAttemptPacer()).runOnce()).isEqualTo(1);

        assertThat(sent.get().occurredAt()).isEqualTo(NOW);
        assertDelivery(fixture.deliveryId(), "SENT", 1, null, NOW.plusSeconds(600));
    }

    @Test
    void staleProviderResultCannotOverwriteAConcurrentMergedIncidentVersion() throws Exception {
        Fixture fixture = pushFixture();
        given(authService.isSessionUsable(fixture.sid(), fixture.userId())).willReturn(true);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        given(webPushSender.send(any(), any())).willAnswer(invocation -> {
            enteredProvider.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return DeliveryOutcome.of(DeliveryOutcomeKind.SENT);
        });
        NotificationDeliveryWorker worker = worker(
                lease(), new SlackAttemptPacer());

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(worker::runOnce);
        assertThat(enteredProvider.await(5, TimeUnit.SECONDS)).isTrue();
        jdbc.update("UPDATE incidents SET incident_version = 2 WHERE incident_id = ?", fixture.incidentId());
        jdbc.update("UPDATE notification_deliveries SET incident_version = 2 WHERE id = ?", fixture.deliveryId());
        releaseProvider.countDown();

        assertThat(run.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertDelivery(fixture.deliveryId(), "PENDING", 1, NOW.plusSeconds(5), NOW.plusSeconds(600));
        assertThat(jdbc.queryForObject(
                "SELECT incident_version FROM notification_deliveries WHERE id = ?",
                Long.class, fixture.deliveryId())).isEqualTo(2L);
        assertReceipt(fixture.incidentId(), "WEB_PUSH", fixture.recipientId(), NOW);
    }

    @Test
    void currentSuccessWritesReceiptAtomicallyAndOlderCallbackCannotRewindIt() {
        Fixture fixture = slackFixture(41);
        NotificationDeliveryTransaction.DeliveryClaim claim = claim(fixture, NOW);

        assertThat(deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(10)))
                .isTrue();
        assertDelivery(fixture.deliveryId(), "SENT", 1, null, NOW.plusSeconds(600));
        assertReceipt(fixture.incidentId(), "SLACK", fixture.recipientId(), NOW.plusSeconds(10));

        assertThat(deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(5)))
                .isFalse();
        assertReceipt(fixture.incidentId(), "SLACK", fixture.recipientId(), NOW.plusSeconds(10));
    }

    @Test
    void sentStateFailureRollsBackSuccessReceipt() {
        Fixture fixture = slackFixture(42);
        NotificationDeliveryTransaction.DeliveryClaim claim = claim(fixture, NOW);
        jdbc.execute("""
                CREATE FUNCTION fail_sent_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.status = 'SENT' THEN
                        RAISE EXCEPTION 'injected sent completion failure';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_sent_completion BEFORE UPDATE ON notification_deliveries
                FOR EACH ROW EXECUTE FUNCTION fail_sent_completion()
                """);

        assertThatThrownBy(() -> deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(1)))
                .hasMessageContaining("injected sent completion failure");

        assertThat(receiptCount(fixture.incidentId(), "SLACK", fixture.recipientId())).isZero();
        assertDelivery(fixture.deliveryId(), "PENDING", 1, NOW.plusSeconds(5), NOW.plusSeconds(600));
    }

    @Test
    void nonSentAndRecoverySentOutcomesCreateNoOpeningReceipt() {
        Fixture failed = slackFixture(43);
        NotificationDeliveryTransaction.DeliveryClaim failedClaim = claim(failed, NOW);
        assertThat(deliveryTransaction.complete(
                failedClaim, DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR), NOW))
                .isTrue();
        assertThat(receiptCount(failed.incidentId(), "SLACK", failed.recipientId())).isZero();

        Fixture recovery = slackFixture(44);
        jdbc.update("""
                UPDATE incidents
                SET status = 'RESOLVED', resolved_at = ?, resolution_reason = 'RECOVERED'
                WHERE incident_id = ?
                """, Timestamp.from(NOW), recovery.incidentId());
        jdbc.update("""
                UPDATE notification_deliveries
                SET notification_type = 'INCIDENT_RESOLVED'
                WHERE id = ?
                """, recovery.deliveryId());
        NotificationDeliveryTransaction.DeliveryClaim recoveryClaim = claim(recovery, NOW);
        assertThat(deliveryTransaction.complete(
                recoveryClaim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(1)))
                .isTrue();
        assertThat(receiptCount(recovery.incidentId(), "SLACK", recovery.recipientId())).isZero();
    }

    @Test
    void lateOpeningSuccessAfterRecoveredResolutionCreatesOneFixedWindowRecovery() {
        Fixture fixture = slackFixture(45);
        NotificationDeliveryTransaction.DeliveryClaim claim = claim(fixture, NOW);
        Instant resolvedAt = NOW.plusSeconds(60);
        resolve(fixture, "RECOVERED", resolvedAt, true, true);

        assertThat(deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), resolvedAt.plusSeconds(1)))
                .isFalse();
        assertThat(deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), resolvedAt.plusSeconds(2)))
                .isFalse();

        assertReceipt(fixture.incidentId(), "SLACK", fixture.recipientId(), resolvedAt.plusSeconds(2));
        Map<String, Object> recovery = jdbc.queryForMap("""
                SELECT incident_version, status, attempt_count, next_attempt_at, expires_at, created_at
                FROM notification_deliveries
                WHERE incident_id = ? AND notification_type = 'INCIDENT_RESOLVED'
                  AND notification_webhook_id = ?
                """, fixture.incidentId(), fixture.recipientId());
        assertThat(recovery.get("incident_version")).isEqualTo(2L);
        assertThat(recovery.get("status")).isEqualTo("PENDING");
        assertThat(recovery.get("attempt_count")).isEqualTo(0);
        assertThat(toInstant(recovery.get("next_attempt_at"))).isEqualTo(resolvedAt);
        assertThat(toInstant(recovery.get("expires_at"))).isEqualTo(resolvedAt.plusSeconds(600));
        assertThat(toInstant(recovery.get("created_at"))).isEqualTo(resolvedAt.plusSeconds(1));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                WHERE incident_id = ? AND notification_type = 'INCIDENT_RESOLVED'
                """, Integer.class, fixture.incidentId())).isOne();
    }

    @Test
    void lateSuccessRecordsTruthWithoutRecoveryForIneligibleCurrentState() {
        Fixture administrative = slackFixture(46);
        NotificationDeliveryTransaction.DeliveryClaim administrativeClaim = claim(administrative, NOW);
        resolve(administrative, "POLICY_CHANGED", NOW.plusSeconds(10), true, true);
        assertThat(deliveryTransaction.complete(administrativeClaim,
                DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(11))).isFalse();
        assertReceipt(administrative.incidentId(), "SLACK", administrative.recipientId(), NOW.plusSeconds(11));
        assertThat(recoveryCount(administrative.incidentId())).isZero();

        Fixture expired = slackFixture(47);
        NotificationDeliveryTransaction.DeliveryClaim expiredClaim = claim(expired, NOW);
        Instant expiredResolution = NOW.plusSeconds(10);
        resolve(expired, "RECOVERED", expiredResolution, true, true);
        assertThat(deliveryTransaction.complete(expiredClaim,
                DeliveryOutcome.of(DeliveryOutcomeKind.SENT), expiredResolution.plusSeconds(600))).isFalse();
        assertReceipt(expired.incidentId(), "SLACK", expired.recipientId(),
                expiredResolution.plusSeconds(600));
        assertThat(recoveryCount(expired.incidentId())).isZero();

        Fixture disabledTarget = slackFixture(48);
        NotificationDeliveryTransaction.DeliveryClaim disabledTargetClaim = claim(disabledTarget, NOW);
        resolve(disabledTarget, "RECOVERED", NOW.plusSeconds(20), false, true);
        assertThat(deliveryTransaction.complete(disabledTargetClaim,
                DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(21))).isFalse();
        assertReceipt(disabledTarget.incidentId(), "SLACK", disabledTarget.recipientId(), NOW.plusSeconds(21));
        assertThat(recoveryCount(disabledTarget.incidentId())).isZero();

        Fixture disabledRecipient = slackFixture(49);
        NotificationDeliveryTransaction.DeliveryClaim disabledRecipientClaim = claim(disabledRecipient, NOW);
        resolve(disabledRecipient, "RECOVERED", NOW.plusSeconds(30), true, false);
        assertThat(deliveryTransaction.complete(disabledRecipientClaim,
                DeliveryOutcome.of(DeliveryOutcomeKind.SENT), NOW.plusSeconds(31))).isFalse();
        assertReceipt(disabledRecipient.incidentId(), "SLACK", disabledRecipient.recipientId(), NOW.plusSeconds(31));
        assertThat(recoveryCount(disabledRecipient.incidentId())).isZero();

        Fixture revokedSession = pushFixture();
        NotificationDeliveryTransaction.DeliveryClaim revokedSessionClaim = claim(revokedSession, NOW);
        given(authService.isSessionUsable(revokedSession.sid(), revokedSession.userId())).willReturn(false);
        Instant revokedResolution = NOW.plusSeconds(40);
        jdbc.update("""
                UPDATE incidents
                SET status = 'RESOLVED', last_observed_at = ?, resolved_at = ?,
                    resolution_reason = 'RECOVERED', incident_version = 2
                WHERE incident_id = ?
                """, Timestamp.from(revokedResolution), Timestamp.from(revokedResolution),
                revokedSession.incidentId());
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, sent_at = NULL,
                    last_error_code = NULL
                WHERE id = ?
                """, revokedSession.deliveryId());
        assertThat(deliveryTransaction.complete(revokedSessionClaim,
                DeliveryOutcome.of(DeliveryOutcomeKind.SENT), revokedResolution.plusSeconds(1))).isFalse();
        assertReceipt(revokedSession.incidentId(), "WEB_PUSH", revokedSession.recipientId(),
                revokedResolution.plusSeconds(1));
        assertThat(recoveryCount(revokedSession.incidentId())).isZero();
    }

    @Test
    void concurrentRecoveredSchedulingAndLateCompletionConvergeToOneRecovery() throws Exception {
        Fixture fixture = slackFixture(50);
        NotificationDeliveryTransaction.DeliveryClaim claim = claim(fixture, NOW);
        Instant resolvedAt = NOW.plusSeconds(60);
        markResolved(fixture, "RECOVERED", resolvedAt);
        clock.set(resolvedAt.plusSeconds(1));
        NotificationIncidentEvent event = recoveredEvent(fixture, resolvedAt);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Void> scheduling = CompletableFuture.runAsync(() -> {
            awaitStart(ready, start);
            schedulingTransaction.process("stream:incidents", event);
        });
        CompletableFuture<Boolean> completion = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return deliveryTransaction.complete(
                    claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), resolvedAt.plusSeconds(1));
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        CompletableFuture.allOf(scheduling, completion).get(10, TimeUnit.SECONDS);
        assertThat(completion.get()).isFalse();
        assertReceipt(fixture.incidentId(), "SLACK", fixture.recipientId(), resolvedAt.plusSeconds(1));
        assertThat(recoveryCount(fixture.incidentId())).isOne();
    }

    @Test
    void retentionAndLateCompletionDoNotDeadlockOrOrphanReceipt() throws Exception {
        Fixture fixture = slackFixture(51);
        NotificationDeliveryTransaction.DeliveryClaim claim = claim(fixture, NOW);
        Instant resolvedAt = NOW.plusSeconds(1);
        markResolved(fixture, "RECOVERED", resolvedAt);
        Instant retentionNow = NOW.plus(182, java.time.temporal.ChronoUnit.DAYS);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Boolean> completion = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return deliveryTransaction.complete(
                    claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), resolvedAt.plusSeconds(1));
        });
        CompletableFuture<PartCRetentionService.CleanupResult> retention =
                CompletableFuture.supplyAsync(() -> {
                    awaitStart(ready, start);
                    return retentionService.purge(retentionNow);
                });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        CompletableFuture.allOf(completion, retention).get(10, TimeUnit.SECONDS);
        PartCRetentionService.CleanupResult concurrentCleanup = retention.get();
        PartCRetentionService.CleanupResult followUpCleanup = retentionService.purge(retentionNow);
        assertThat(concurrentCleanup.incidents() + followUpCleanup.incidents()).isOne();
        assertThat(concurrentCleanup.deliveries() + followUpCleanup.deliveries()).isBetween(1, 2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM incidents WHERE incident_id = ?",
                Integer.class, fixture.incidentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE incident_id = ?",
                Integer.class, fixture.incidentId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_success_receipts WHERE incident_id = ?",
                Integer.class, fixture.incidentId())).isZero();
        assertThat(deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), resolvedAt.plusSeconds(2)))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_success_receipts WHERE incident_id = ?",
                Integer.class, fixture.incidentId())).isZero();
    }

    @Test
    void explicitDeleteAndDeliveryClaimUseOneLockOrderWithoutDeadlock() throws Exception {
        Fixture fixture = pushFixture();
        CountDownLatch deliveryLocked = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> deletion = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            recipients.lockPendingPushDeliveries(fixture.recipientId());
            deliveryLocked.countDown();
            try {
                assertThat(releaseDelete.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            recipients.findOwnedPushForUpdate(fixture.recipientId(), fixture.userId()).orElseThrow();
            recipients.tombstonePush(fixture.recipientId(), NOW);
        }));
        assertThat(deliveryLocked.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch claimStarted = new CountDownLatch(1);
        CompletableFuture<java.util.Optional<NotificationDeliveryTransaction.DeliveryClaim>> claim =
                CompletableFuture.supplyAsync(() -> {
                    claimStarted.countDown();
                    return deliveryTransaction.claim(new NotificationDeliveryStore.DueCandidate(
                            fixture.deliveryId(), fixture.incidentId(), fixture.targetId()), NOW);
                });
        assertThat(claimStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(claim).isNotDone();

        releaseDelete.countDown();
        deletion.get(5, TimeUnit.SECONDS);
        assertThat(claim.get(5, TimeUnit.SECONDS)).isEmpty();
        assertDelivery(fixture.deliveryId(), "CANCELLED", 0, null, NOW.plusSeconds(600));
        assertThat(jdbc.queryForObject(
                "SELECT enabled FROM push_subscriptions WHERE id = ?",
                Boolean.class, fixture.recipientId())).isFalse();
    }

    private NotificationDeliveryWorker worker(PostgresDeliveryLease lease, SlackAttemptPacer pacer) {
        return new NotificationDeliveryWorker(
                lease, deliveryTransaction, secrets, webPushSender, slackSender,
                slackPolicy, pacer, clock);
    }

    private NotificationDeliveryTransaction.DeliveryClaim claim(Fixture fixture, Instant now) {
        return deliveryTransaction.claim(new NotificationDeliveryStore.DueCandidate(
                fixture.deliveryId(), fixture.incidentId(), fixture.targetId()), now).orElseThrow();
    }

    private void resolve(
            Fixture fixture,
            String reason,
            Instant resolvedAt,
            boolean targetActive,
            boolean recipientActive
    ) {
        markResolved(fixture, reason, resolvedAt);
        jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', next_attempt_at = NULL, sent_at = NULL,
                    last_error_code = NULL
                WHERE id = ?
                """, fixture.deliveryId());
        if (!targetActive) {
            jdbc.update("UPDATE database_configs SET enabled = false WHERE id = ?", fixture.targetId());
        }
        if (!recipientActive) {
            jdbc.update("UPDATE notification_webhooks SET enabled = false WHERE id = ?", fixture.recipientId());
        }
    }

    private void markResolved(Fixture fixture, String reason, Instant resolvedAt) {
        jdbc.update("""
                UPDATE incidents
                SET status = 'RESOLVED', last_observed_at = ?, resolved_at = ?,
                    resolution_reason = ?, incident_version = 2
                WHERE incident_id = ?
                """, Timestamp.from(resolvedAt), Timestamp.from(resolvedAt), reason, fixture.incidentId());
    }

    private NotificationIncidentEvent recoveredEvent(Fixture fixture, Instant resolvedAt) {
        IncidentEventPayload payload = new IncidentEventPayload(
                resolvedAt,
                null,
                fixture.incidentId(),
                fixture.targetId(),
                "delivery-db",
                RuleId.CONNECTION_RATIO,
                RuleType.CONNECTION_RATIO_EXCEEDED,
                IncidentSeverity.CRITICAL,
                IncidentStatus.RESOLVED,
                NOW,
                resolvedAt,
                resolvedAt,
                ResolutionReason.RECOVERED,
                "activeConnectionsRatio",
                new BigDecimal("0.91"),
                new BigDecimal("0.90"),
                null,
                "threshold exceeded",
                2,
                null);
        return new NotificationIncidentEvent(
                UUID.randomUUID(), NotificationIncidentEvent.Type.RESOLVED,
                resolvedAt.plusMillis(1), payload);
    }

    private void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test start timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int recoveryCount(UUID incidentId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                WHERE incident_id = ? AND notification_type = 'INCIDENT_RESOLVED'
                """, Integer.class, incidentId);
    }

    private PostgresDeliveryLease lease() {
        PostgresDeliveryLease value = new PostgresDeliveryLease(dataSource);
        leases.add(value);
        return value;
    }

    private Fixture pushFixture() {
        IncidentFixture incident = incident();
        long userId = jdbc.queryForObject("""
                INSERT INTO users
                    (email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'delivery', 'hash', ?, true, 1, ?, ?) RETURNING id
                """, Long.class, "delivery-" + UUID.randomUUID() + "@example.com", UserRole.USER.name(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        UUID sid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions
                    (sid, user_id, current_refresh_hash, created_at, expires_at, revoked_at, auth_version)
                VALUES (?, ?, ?, ?, ?, NULL, 1)
                """, sid, userId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(7200)));
        byte[] hash = sha256(ENDPOINT);
        long pushId = jdbc.queryForObject("""
                INSERT INTO push_subscriptions
                    (user_id, sid, endpoint_hash, payload_key_version, payload_nonce,
                     payload_ciphertext, expiration_time, enabled, created_at, updated_at)
                VALUES (?, ?, ?, 1, decode(repeat('00', 12), 'hex'),
                        decode(repeat('11', 17), 'hex'), ?, true, ?, ?) RETURNING id
                """, Long.class, userId, sid, hash, NOW.plusSeconds(7200).toEpochMilli(),
                Timestamp.from(NOW), Timestamp.from(NOW));
        long deliveryId = insertDelivery(incident.id(), "WEB_PUSH", pushId, null);
        return new Fixture(incident.id(), incident.target(), deliveryId, pushId, userId, sid);
    }

    private Fixture slackFixture(int marker) {
        IncidentFixture incident = incident();
        long webhookId = insertWebhook(marker);
        long deliveryId = insertDelivery(incident.id(), "SLACK", null, webhookId);
        return new Fixture(incident.id(), incident.target(), deliveryId, webhookId, 0, null);
    }

    private Fixture additionalSlackDelivery(UUID incidentId, int marker) {
        long webhookId = insertWebhook(marker);
        long deliveryId = insertDelivery(incidentId, "SLACK", null, webhookId);
        long targetId = jdbc.queryForObject(
                "SELECT database_config_id FROM incidents WHERE incident_id = ?", Long.class, incidentId);
        return new Fixture(incidentId, targetId, deliveryId, webhookId, 0, null);
    }

    private long insertWebhook(int marker) {
        return jdbc.queryForObject("""
                INSERT INTO notification_webhooks
                    (name, provider, url_key_version, url_nonce, url_ciphertext,
                     enabled, created_at, updated_at)
                VALUES (?, 'SLACK', 1, decode(repeat('00', 12), 'hex'),
                        decode(repeat('11', 17), 'hex'), true, ?, ?) RETURNING id
                """, Long.class, "webhook-" + marker, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private IncidentFixture incident() {
        long target = jdbc.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port, status, config_version)
                VALUES (5, ?, true, '127.0.0.1', ?, 13306, 'UNKNOWN', 1) RETURNING id
                """, Long.class, Timestamp.from(NOW), "delivery-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, risk_level, activation_at, updated_at)
                VALUES (?, 1, 1, true, false, 'UP', 'FRESH', 'CRITICAL', ?, ?)
                """, target, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, '[]'::jsonb, 60, 300, ?, ?)
                """, target, Timestamp.from(NOW), Timestamp.from(NOW));
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents
                    (incident_id, database_config_id, database_name, rule_id, rule_type, severity,
                     status, opened_at, last_observed_at, metric_name, metric_value,
                     threshold_value, message, incident_version)
                VALUES (?, ?, 'delivery-db', 'CONNECTION_RATIO', 'CONNECTION_RATIO_EXCEEDED',
                        'CRITICAL', 'OPEN', ?, ?, 'activeConnectionsRatio', 0.91, 0.90,
                        'threshold exceeded', 1)
                """, incidentId, target, Timestamp.from(NOW), Timestamp.from(NOW));
        return new IncidentFixture(incidentId, target);
    }

    private long insertDelivery(UUID incidentId, String channel, Long pushId, Long webhookId) {
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries
                    (incident_id, incident_version, notification_type, channel,
                     push_subscription_id, notification_webhook_id, status, attempt_count,
                     next_attempt_at, expires_at, created_at)
                VALUES (?, 1, 'INCIDENT_OPENED', ?, ?, ?, 'PENDING', 0, ?, ?, ?) RETURNING id
                """, Long.class, incidentId, channel, pushId, webhookId, Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(600)), Timestamp.from(NOW));
    }

    private void assertDelivery(long id, String status, int attempts, Instant nextAttempt, Instant expiresAt) {
        var row = jdbc.queryForMap("""
                SELECT status, attempt_count, next_attempt_at, expires_at
                FROM notification_deliveries WHERE id = ?
                """, id);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("attempt_count")).isEqualTo(attempts);
        assertThat(toInstant(row.get("next_attempt_at"))).isEqualTo(nextAttempt);
        assertThat(toInstant(row.get("expires_at"))).isEqualTo(expiresAt);
    }

    private void assertReceipt(
            UUID incidentId,
            String channel,
            long recipientId,
            Instant successfulAt
    ) {
        Timestamp actual = jdbc.queryForObject("""
                SELECT last_successful_open_or_increase_at
                FROM notification_success_receipts
                WHERE incident_id = ? AND channel = ? AND recipient_id = ?
                """, Timestamp.class, incidentId, channel, recipientId);
        assertThat(actual).isNotNull();
        assertThat(actual.toInstant()).isEqualTo(successfulAt);
    }

    private int receiptCount(UUID incidentId, String channel, long recipientId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_success_receipts
                WHERE incident_id = ? AND channel = ? AND recipient_id = ?
                """, Integer.class, incidentId, channel, recipientId);
    }

    private Instant toInstant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record IncidentFixture(UUID id, long target) {
    }

    private record Fixture(
            UUID incidentId,
            long targetId,
            long deliveryId,
            long recipientId,
            long userId,
            UUID sid
    ) {
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is UTC-only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig configuration = new HikariConfig();
            configuration.setDataSource(EmbeddedPostgresSupport.postgres().getPostgresDatabase());
            configuration.setMaximumPoolSize(8);
            configuration.setMinimumIdle(0);
            configuration.setPoolName("notification-delivery-test-pool");
            return new HikariDataSource(configuration);
        }

        @Bean
        MutableClock clock() {
            return new MutableClock();
        }
    }
}
