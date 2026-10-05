package com.example.monitoring.notification.scheduling;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.common.outbox.ProcessedEventStore;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore;
import com.example.monitoring.notification.delivery.NotificationDeliveryTransaction;
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
import com.example.monitoring.risk.contract.SeverityTransition;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({EmbeddedPostgresSupport.Config.class, ProcessedEventStore.class,
        NotificationSchedulingStore.class, NotificationSchedulingTransaction.class,
        NotificationDeliveryStore.class, NotificationDeliveryTransaction.class,
        PartCRetentionStore.class, PartCRetentionService.class,
        NotificationSchedulingIntegrationTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationSchedulingIntegrationTest {

    private static final String STREAM = "stream:incidents";
    private static final String GROUP = "cg:notification";
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00.000Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationSchedulingTransaction transaction;
    @Autowired private NotificationDeliveryTransaction deliveryTransaction;
    @Autowired private PartCRetentionService retentionService;
    @Autowired private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM processed_events WHERE stream = ? AND consumer_group = ?", STREAM, GROUP);
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
    }

    @Test
    void schedulesAllActiveRecipientsMergesWithinOriginalWindowAndReplacesFatal() {
        long target = target(300);
        Recipient firstPush = pushRecipient("first@example.com", 1);
        pushRecipient("second@example.com", 2);
        webhook();
        UUID incidentId = UUID.randomUUID();
        insertIncident(incidentId, target, RuleId.CONNECTION_RATIO, IncidentSeverity.WARNING, 1, NOW);

        NotificationIncidentEvent created = event(
                NotificationIncidentEvent.Type.CREATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 1, NOW, null, null, null);
        transaction.process(STREAM, created);
        transaction.process(STREAM, created);

        assertThat(deliveries(incidentId, "INCIDENT_OPENED"))
                .hasSize(3)
                .allSatisfy(row -> {
                    assertThat(row.get("status")).isEqualTo("PENDING");
                    assertThat(row.get("next_attempt_at")).isEqualTo(NOW);
                    assertThat(row.get("created_at")).isEqualTo(NOW);
                    assertThat(row.get("expires_at")).isEqualTo(NOW.plusSeconds(600));
                });
        assertThat(countProcessed()).isEqualTo(1);

        jdbc.update("""
                UPDATE notification_deliveries
                SET status = CASE WHEN push_subscription_id = ? THEN 'SENT' ELSE 'FAILED' END,
                    attempt_count = 1,
                    next_attempt_at = NULL,
                    sent_at = CASE WHEN push_subscription_id = ? THEN ?::timestamptz ELSE NULL END,
                    last_error_code = CASE WHEN push_subscription_id = ? THEN NULL ELSE 'PROVIDER_ERROR' END
                WHERE incident_id = ? AND notification_type = 'INCIDENT_OPENED'
                """, firstPush.id(), firstPush.id(), Timestamp.from(NOW.plusSeconds(1)),
                firstPush.id(), incidentId);
        insertReceipt(incidentId, "WEB_PUSH", firstPush.id(), NOW.plusSeconds(1));

        Instant firstIncreaseAt = NOW.plusSeconds(60);
        clock.set(firstIncreaseAt);
        updateOpenIncident(incidentId, IncidentSeverity.CRITICAL, 2, firstIncreaseAt);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.UPDATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.CRITICAL, IncidentStatus.OPEN, 2, firstIncreaseAt,
                SeverityTransition.INCREASED, null, null));

        Map<String, Object> cooled = deliveryForPush(incidentId, "SEVERITY_INCREASED", firstPush.id(), "PENDING");
        assertThat(cooled.get("next_attempt_at")).isEqualTo(NOW.plusSeconds(301));
        assertThat(cooled.get("created_at")).isEqualTo(firstIncreaseAt);
        assertThat(cooled.get("expires_at")).isEqualTo(NOW.plusSeconds(901));

        Instant mergedAt = NOW.plusSeconds(120);
        clock.set(mergedAt);
        updateOpenIncident(incidentId, IncidentSeverity.CRITICAL, 3, mergedAt);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.UPDATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.CRITICAL, IncidentStatus.OPEN, 3, mergedAt,
                SeverityTransition.INCREASED, null, null));

        Map<String, Object> merged = deliveryForPush(incidentId, "SEVERITY_INCREASED", firstPush.id(), "PENDING");
        assertThat(merged.get("incident_version")).isEqualTo(3L);
        assertThat(merged.get("next_attempt_at")).isEqualTo(NOW.plusSeconds(301));
        assertThat(merged.get("created_at")).isEqualTo(firstIncreaseAt);
        assertThat(merged.get("expires_at")).isEqualTo(NOW.plusSeconds(901));
        assertThat(deliveries(incidentId, "SEVERITY_INCREASED")).hasSize(3);

        Instant fatalAt = NOW.plusSeconds(180);
        clock.set(fatalAt);
        updateOpenIncident(incidentId, IncidentSeverity.FATAL, 4, fatalAt);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.UPDATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.FATAL, IncidentStatus.OPEN, 4, fatalAt,
                SeverityTransition.INCREASED, null, null));

        assertThat(deliveries(incidentId, "SEVERITY_INCREASED"))
                .filteredOn(row -> row.get("status").equals("CANCELLED"))
                .hasSize(3);
        assertThat(deliveries(incidentId, "SEVERITY_INCREASED"))
                .filteredOn(row -> row.get("status").equals("PENDING"))
                .hasSize(3)
                .allSatisfy(row -> {
                    assertThat(row.get("incident_version")).isEqualTo(4L);
                    assertThat(row.get("next_attempt_at")).isEqualTo(fatalAt);
                    assertThat(row.get("expires_at")).isEqualTo(fatalAt.plusSeconds(600));
                });

        Instant resolvedAt = NOW.plusSeconds(240);
        clock.set(resolvedAt);
        resolveIncident(incidentId, 5, resolvedAt, ResolutionReason.RECOVERED);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.RESOLVED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.FATAL, IncidentStatus.RESOLVED, 5, resolvedAt,
                null, resolvedAt, ResolutionReason.RECOVERED));

        assertThat(deliveries(incidentId, "INCIDENT_RESOLVED"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("push_subscription_id")).isEqualTo(firstPush.id());
                    assertThat(row.get("status")).isEqualTo("PENDING");
                    assertThat(row.get("next_attempt_at")).isEqualTo(resolvedAt);
                    assertThat(row.get("expires_at")).isEqualTo(resolvedAt.plusSeconds(600));
                });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                WHERE incident_id = ? AND notification_type = 'SEVERITY_INCREASED' AND status = 'PENDING'
                """, Long.class, incidentId)).isZero();
    }

    @Test
    void decreaseAndAdministrativeResolutionCancelPendingWithoutCreatingNotifications() {
        long target = target(300);
        pushRecipient("owner@example.com", 3);
        webhook();
        UUID incidentId = UUID.randomUUID();
        insertIncident(incidentId, target, RuleId.CONNECTION_RATIO, IncidentSeverity.WARNING, 1, NOW);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.CREATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 1, NOW, null, null, null));

        Instant increasedAt = NOW.plusSeconds(60);
        clock.set(increasedAt);
        updateOpenIncident(incidentId, IncidentSeverity.CRITICAL, 2, increasedAt);
        NotificationIncidentEvent increased = event(
                NotificationIncidentEvent.Type.UPDATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.CRITICAL, IncidentStatus.OPEN, 2, increasedAt,
                SeverityTransition.INCREASED, null, null);
        transaction.process(STREAM, increased);

        Instant decreasedAt = NOW.plusSeconds(120);
        clock.set(decreasedAt);
        updateOpenIncident(incidentId, IncidentSeverity.WARNING, 3, decreasedAt);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.UPDATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 3, decreasedAt,
                SeverityTransition.DECREASED, null, null));

        assertThat(deliveries(incidentId, "SEVERITY_INCREASED"))
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("CANCELLED"));

        int countBeforeStaleReplay = countDeliveries(incidentId);
        transaction.process(STREAM, new NotificationIncidentEvent(
                UUID.randomUUID(), increased.eventType(), increased.publishedAt(), increased.incident()));
        assertThat(countDeliveries(incidentId)).isEqualTo(countBeforeStaleReplay);

        Instant resolvedAt = NOW.plusSeconds(180);
        clock.set(resolvedAt);
        resolveIncident(incidentId, 4, resolvedAt, ResolutionReason.POLICY_CHANGED);
        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.RESOLVED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.RESOLVED, 4, resolvedAt,
                null, resolvedAt, ResolutionReason.POLICY_CHANGED));

        assertThat(deliveries(incidentId, "INCIDENT_RESOLVED")).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                WHERE incident_id = ? AND status = 'PENDING'
                """, Long.class, incidentId)).isZero();
    }

    @Test
    void serializesInterleavedSameTargetEventsWithoutLosingEitherIncident() throws Exception {
        long target = target(300);
        pushRecipient("parallel@example.com", 4);
        webhook();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertIncident(first, target, RuleId.CONNECTION_RATIO, IncidentSeverity.WARNING, 1, NOW);
        insertIncident(second, target, RuleId.SLOW_QUERY_RATE, IncidentSeverity.WARNING, 1, NOW);
        NotificationIncidentEvent firstEvent = event(
                NotificationIncidentEvent.Type.CREATED, first, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 1, NOW, null, null, null);
        NotificationIncidentEvent secondEvent = event(
                NotificationIncidentEvent.Type.CREATED, second, target, RuleId.SLOW_QUERY_RATE,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 1, NOW, null, null, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> left = CompletableFuture.runAsync(
                    () -> transaction.process(STREAM, firstEvent), executor);
            CompletableFuture<Void> right = CompletableFuture.runAsync(
                    () -> transaction.process(STREAM, secondEvent), executor);
            CompletableFuture.allOf(left, right).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(countDeliveries(first)).isEqualTo(2);
        assertThat(countDeliveries(second)).isEqualTo(2);
        assertThat(countProcessed()).isEqualTo(2);
    }

    @Test
    void doesNotBackfillRecipientsRegisteredAfterEventOccurrence() {
        long target = target(300);
        Recipient existing = pushRecipient("existing@example.com", 5, NOW.minusSeconds(1));
        Recipient late = pushRecipient("late@example.com", 6, NOW.plusSeconds(10));
        UUID incidentId = UUID.randomUUID();
        insertIncident(incidentId, target, RuleId.CONNECTION_RATIO, IncidentSeverity.WARNING, 1, NOW);
        NotificationIncidentEvent event = event(
                NotificationIncidentEvent.Type.CREATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 1, NOW, null, null, null);
        clock.set(NOW.plusSeconds(20));

        transaction.process(STREAM, event);

        assertThat(deliveries(incidentId, "INCIDENT_OPENED"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("push_subscription_id")).isEqualTo(existing.id()));
        assertThat(deliveries(incidentId, "INCIDENT_OPENED"))
                .noneSatisfy(row -> assertThat(row.get("push_subscription_id")).isEqualTo(late.id()));
    }

    @Test
    void retainedSuccessReceiptAllowsRecoveryAfterThirtyDayDeliveryPurge() {
        Instant openedAt = NOW.minus(31, ChronoUnit.DAYS);
        clock.set(openedAt);
        long target = target(300);
        long webhookId = webhook(openedAt);
        long unsentWebhookId = webhook(openedAt);
        UUID incidentId = UUID.randomUUID();
        insertIncident(incidentId, target, RuleId.CONNECTION_RATIO, IncidentSeverity.WARNING, 1, openedAt);

        transaction.process(STREAM, event(
                NotificationIncidentEvent.Type.CREATED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.OPEN, 1, openedAt, null, null, null));

        NotificationDeliveryStore.DueCandidate candidate = deliveryTransaction
                .dueCandidates(openedAt, 10)
                .stream()
                .filter(value -> value.incidentId().equals(incidentId))
                .findFirst()
                .orElseThrow();
        NotificationDeliveryTransaction.DeliveryClaim claim = deliveryTransaction
                .claim(candidate, openedAt)
                .orElseThrow();
        assertThat(deliveryTransaction.complete(
                claim, DeliveryOutcome.of(DeliveryOutcomeKind.SENT), openedAt.plusSeconds(1)))
                .isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_deliveries
                WHERE incident_id = ? AND notification_webhook_id = ? AND status = 'SENT'
                """, Integer.class, incidentId, webhookId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_success_receipts
                WHERE incident_id = ? AND channel = 'SLACK' AND recipient_id = ?
                """, Integer.class, incidentId, webhookId)).isOne();

        clock.set(NOW);
        PartCRetentionService.CleanupResult cleanup = retentionService.purge(NOW);
        assertThat(cleanup.deliveries()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE incident_id = ?",
                Integer.class, incidentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM incidents WHERE incident_id = ? AND status = 'OPEN'",
                Integer.class, incidentId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_success_receipts
                WHERE incident_id = ? AND channel = 'SLACK' AND recipient_id = ?
                """, Integer.class, incidentId, webhookId)).isOne();

        resolveIncident(incidentId, 2, NOW, ResolutionReason.RECOVERED);
        NotificationSchedulingTransaction restarted = new NotificationSchedulingTransaction(
                new NotificationSchedulingStore(jdbc), new ProcessedEventStore(jdbc), clock);
        NotificationIncidentEvent recovered = event(
                NotificationIncidentEvent.Type.RESOLVED, incidentId, target, RuleId.CONNECTION_RATIO,
                IncidentSeverity.WARNING, IncidentStatus.RESOLVED, 2, NOW,
                null, NOW, ResolutionReason.RECOVERED);
        restarted.process(STREAM, recovered);
        restarted.process(STREAM, recovered);

        assertThat(deliveries(incidentId, "INCIDENT_RESOLVED"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("notification_webhook_id")).isEqualTo(webhookId);
                    assertThat(row.get("notification_webhook_id")).isNotEqualTo(unsentWebhookId);
                    assertThat(row.get("status")).isEqualTo("PENDING");
                });
    }

    private void insertReceipt(UUID incidentId, String channel, long recipientId, Instant successfulAt) {
        jdbc.update("""
                INSERT INTO notification_success_receipts
                    (incident_id, channel, push_subscription_id, notification_webhook_id,
                     last_successful_open_or_increase_at)
                VALUES (?, ?, ?, ?, ?)
                """, incidentId, channel,
                "WEB_PUSH".equals(channel) ? recipientId : null,
                "SLACK".equals(channel) ? recipientId : null,
                Timestamp.from(successfulAt));
    }

    private long target(int cooldownSeconds) {
        long id = jdbc.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port, status, config_version)
                VALUES (5, ?, true, '127.0.0.1', 'schedule-db', 13306, 'UNKNOWN', 1)
                RETURNING id
                """, Long.class, Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO monitoring_states
                    (database_config_id, config_version, state_version, enabled, deleted,
                     connection_status, data_freshness, risk_level, activation_at, updated_at)
                VALUES (?, 1, 1, true, false, 'UP', 'FRESH', 'WARNING', ?, ?)
                """, id, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO risk_policies
                    (database_config_id, version, rules, stale_after_seconds,
                     notification_cooldown_seconds, created_at, updated_at)
                VALUES (?, 1, '[]'::jsonb, 60, ?, ?, ?)
                """, id, cooldownSeconds, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private Recipient pushRecipient(String email, int marker) {
        return pushRecipient(email, marker, NOW);
    }

    private Recipient pushRecipient(String email, int marker, Instant createdAt) {
        long userId = jdbc.queryForObject("""
                INSERT INTO users
                    (email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'recipient', 'hash', ?, true, 1, ?, ?) RETURNING id
                """, Long.class, email, UserRole.USER.name(), Timestamp.from(createdAt), Timestamp.from(createdAt));
        UUID sid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions
                    (sid, user_id, current_refresh_hash, created_at, expires_at, revoked_at, auth_version)
                VALUES (?, ?, ?, ?, ?, NULL, 1)
                """, sid, userId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                Timestamp.from(createdAt), Timestamp.from(NOW.plusSeconds(7200)));
        byte[] hash = new byte[32];
        hash[31] = (byte) marker;
        long id = jdbc.queryForObject("""
                INSERT INTO push_subscriptions
                    (user_id, sid, endpoint_hash, payload_key_version, payload_nonce,
                     payload_ciphertext, expiration_time, enabled, created_at, updated_at)
                VALUES (?, ?, ?, 1, decode(repeat('00', 12), 'hex'),
                        decode(repeat('11', 17), 'hex'), ?, true, ?, ?) RETURNING id
                """, Long.class, userId, sid, hash, NOW.plusSeconds(7200).toEpochMilli(),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return new Recipient(id, userId, sid);
    }

    private long webhook() {
        return webhook(NOW);
    }

    private long webhook(Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notification_webhooks
                    (name, provider, url_key_version, url_nonce, url_ciphertext, enabled, created_at, updated_at)
                VALUES ('operations', 'SLACK', 1, decode(repeat('00', 12), 'hex'),
                        decode(repeat('11', 17), 'hex'), true, ?, ?) RETURNING id
                """, Long.class, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertIncident(UUID id, long target, RuleId ruleId, IncidentSeverity severity,
                                long version, Instant observedAt) {
        jdbc.update("""
                INSERT INTO incidents
                    (incident_id, database_config_id, database_name, rule_id, rule_type, severity, status,
                     opened_at, last_observed_at, metric_name, metric_value, threshold_value,
                     message, incident_version)
                VALUES (?, ?, 'schedule-db', ?, ?, ?, 'OPEN', ?, ?, 'metric', 0.91, 0.90,
                        'threshold exceeded', ?)
                """, id, target, ruleId.name(), ruleType(ruleId).name(), severity.name(),
                Timestamp.from(observedAt), Timestamp.from(observedAt), version);
    }

    private void updateOpenIncident(UUID id, IncidentSeverity severity, long version, Instant observedAt) {
        jdbc.update("""
                UPDATE incidents
                SET severity = ?, status = 'OPEN', last_observed_at = ?, resolved_at = NULL,
                    resolution_reason = NULL, incident_version = ?
                WHERE incident_id = ?
                """, severity.name(), Timestamp.from(observedAt), version, id);
    }

    private void resolveIncident(UUID id, long version, Instant resolvedAt, ResolutionReason reason) {
        jdbc.update("""
                UPDATE incidents
                SET status = 'RESOLVED', last_observed_at = ?, resolved_at = ?, resolution_reason = ?,
                    incident_version = ?
                WHERE incident_id = ?
                """, Timestamp.from(resolvedAt), Timestamp.from(resolvedAt), reason.name(), version, id);
    }

    private NotificationIncidentEvent event(
            NotificationIncidentEvent.Type type,
            UUID incidentId,
            long target,
            RuleId ruleId,
            IncidentSeverity severity,
            IncidentStatus status,
            long version,
            Instant timestamp,
            SeverityTransition transition,
            Instant resolvedAt,
            ResolutionReason reason
    ) {
        IncidentEventPayload payload = new IncidentEventPayload(
                timestamp, null, incidentId, target, "schedule-db", ruleId, ruleType(ruleId), severity,
                status, NOW, timestamp, resolvedAt, reason, "metric", new BigDecimal("0.91"),
                new BigDecimal("0.90"), null, "threshold exceeded", version, transition);
        return new NotificationIncidentEvent(UUID.randomUUID(), type, timestamp.plusMillis(50), payload);
    }

    private List<Map<String, Object>> deliveries(UUID incidentId, String type) {
        return jdbc.queryForList("""
                SELECT incident_version, notification_type, channel, push_subscription_id,
                       notification_webhook_id, status, next_attempt_at, expires_at, created_at
                FROM notification_deliveries
                WHERE incident_id = ? AND notification_type = ?
                ORDER BY id
                """, incidentId, type).stream().map(this::normalizeTimes).toList();
    }

    private Map<String, Object> deliveryForPush(UUID incidentId, String type, long pushId, String status) {
        return normalizeTimes(jdbc.queryForMap("""
                SELECT incident_version, notification_type, channel, push_subscription_id,
                       notification_webhook_id, status, next_attempt_at, expires_at, created_at
                FROM notification_deliveries
                WHERE incident_id = ? AND notification_type = ? AND push_subscription_id = ? AND status = ?
                """, incidentId, type, pushId, status));
    }

    private Map<String, Object> normalizeTimes(Map<String, Object> source) {
        java.util.LinkedHashMap<String, Object> normalized = new java.util.LinkedHashMap<>(source);
        for (String field : List.of("next_attempt_at", "expires_at", "created_at")) {
            if (normalized.get(field) instanceof Timestamp timestamp) {
                normalized.put(field, timestamp.toInstant());
            }
        }
        return normalized;
    }

    private int countDeliveries(UUID incidentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE incident_id = ?", Integer.class, incidentId);
    }

    private int countProcessed() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM processed_events WHERE stream = ? AND consumer_group = ?
                """, Integer.class, STREAM, GROUP);
    }

    private RuleType ruleType(RuleId ruleId) {
        return switch (ruleId) {
            case CONNECTION_RATIO -> RuleType.CONNECTION_RATIO_EXCEEDED;
            case SLOW_QUERY_RATE -> RuleType.SLOW_QUERIES_HIGH;
            case CONNECTION_FAILURE -> RuleType.CONNECTION_FAILURE;
            case COLLECTION_STALE -> RuleType.COLLECTION_STALE;
        };
    }

    private record Recipient(long id, long userId, UUID sid) {
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock();
        }

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }
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
}
