package com.example.monitoring.notification.delivery;

import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.notification.api.DeliveryStatus;
import com.example.monitoring.notification.api.NotificationChannel;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.DeliveryRow;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.DueCandidate;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.IncidentRow;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.PushRecipientRow;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.StateRow;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.TargetRow;
import com.example.monitoring.notification.delivery.NotificationDeliveryStore.WebhookRecipientRow;
import com.example.monitoring.notification.transport.DeliveryOutcome;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationDeliveryTransaction {

    private static final Duration DELIVERY_WINDOW = Duration.ofSeconds(600);

    private final NotificationDeliveryStore store;
    private final AuthService authService;

    public NotificationDeliveryTransaction(NotificationDeliveryStore store, AuthService authService) {
        this.store = store;
        this.authService = authService;
    }

    public List<DueCandidate> dueCandidates(Instant now, int limit) {
        return store.dueCandidates(now, limit);
    }

    @Transactional
    public Optional<DeliveryClaim> claim(DueCandidate candidate, Instant now) {
        CoordinatedRows rows = lockRows(candidate.targetId(), candidate.incidentId(), candidate.deliveryId());
        if (rows == null) {
            return Optional.empty();
        }
        DeliveryRow delivery = rows.delivery();
        if (!claimable(rows)) {
            cancelIfPending(delivery);
            return Optional.empty();
        }
        if (delivery.nextAttemptAt() == null || delivery.nextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        if (!now.isBefore(delivery.expiresAt())) {
            expire(delivery);
            return Optional.empty();
        }
        if (delivery.attemptCount() >= 4) {
            store.markFailed(delivery.id(), delivery.incidentVersion(), delivery.attemptCount(),
                    priorError(delivery));
            return Optional.empty();
        }
        RecipientData recipient = recipientReference(delivery).orElse(null);
        if (recipient == null) {
            cancelIfPending(delivery);
            return Optional.empty();
        }

        int claimedAttempt = delivery.attemptCount() + 1;
        Instant durableNext = nextAttempt(delivery.expiresAt(), claimedAttempt);
        if (!store.claim(delivery.id(), delivery.incidentVersion(), delivery.attemptCount(),
                claimedAttempt, durableNext)) {
            return Optional.empty();
        }
        return Optional.of(new DeliveryClaim(
                delivery.id(),
                rows.incident().targetId(),
                delivery.incidentId(),
                delivery.incidentVersion(),
                delivery.type(),
                delivery.channel(),
                claimedAttempt,
                durableNext,
                delivery.expiresAt(),
                rows.incident().databaseName(),
                rows.incident().ruleId(),
                rows.incident().severity(),
                occurredAt(rows.incident(), delivery.type()),
                recipient));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<DeliveryClaim> revalidate(DeliveryClaim expected, Instant now) {
        CoordinatedRows rows = readRows(
                expected.targetId(), expected.incidentId(), expected.deliveryId());
        if (rows == null) {
            return Optional.empty();
        }
        DeliveryRow delivery = rows.delivery();
        if (!sameClaim(expected, delivery) || !claimable(rows) || !now.isBefore(delivery.expiresAt())) {
            return Optional.empty();
        }
        RecipientSecret recipient = activeRecipient(delivery, now).orElse(null);
        if (recipient == null) {
            return Optional.empty();
        }
        return Optional.of(new DeliveryClaim(
                expected.deliveryId(),
                expected.targetId(),
                expected.incidentId(),
                expected.incidentVersion(),
                expected.type(),
                expected.channel(),
                expected.claimedAttempt(),
                delivery.nextAttemptAt(),
                expected.expiresAt(),
                rows.incident().databaseName(),
                rows.incident().ruleId(),
                rows.incident().severity(),
                occurredAt(rows.incident(), delivery.type()),
                recipient));
    }

    @Transactional
    public boolean complete(DeliveryClaim expected, DeliveryOutcome outcome, Instant now) {
        CoordinatedRows rows = lockCompletionRows(
                expected.targetId(), expected.incidentId(), expected.deliveryId());
        if (rows == null) {
            return false;
        }
        DeliveryRow delivery = rows.delivery();
        if (outcome.kind() == DeliveryOutcomeKind.SENT
                && recordsOpeningSuccess(expected)
                && trustedRecipient(expected, delivery)) {
            store.upsertSuccessReceipt(
                    expected.incidentId(), expected.channel(), expected.recipient().id(), now);
            reconcileRecovery(rows, expected, now);
        }
        if (delivery == null) {
            return false;
        }
        if (!sameClaim(expected, delivery)) {
            return false;
        }
        if (!claimable(rows)) {
            store.cancel(delivery.id(), expected.incidentVersion(), expected.claimedAttempt());
            return false;
        }
        DeliveryOutcomeKind kind = outcome.kind();
        if (kind == DeliveryOutcomeKind.SENT) {
            return store.markSent(delivery.id(), expected.incidentVersion(),
                    expected.claimedAttempt(), now);
        }
        if (kind == DeliveryOutcomeKind.RECIPIENT_GONE) {
            return store.markFailed(delivery.id(), expected.incidentVersion(),
                    expected.claimedAttempt(), kind.name());
        }
        if (kind == DeliveryOutcomeKind.REJECTED) {
            return store.markFailed(delivery.id(), expected.incidentVersion(),
                    expected.claimedAttempt(), kind.name());
        }
        if (!now.isBefore(expected.expiresAt())) {
            return store.cancelExpired(delivery.id(), expected.incidentVersion(),
                    expected.claimedAttempt(), kind.name());
        }
        if (expected.claimedAttempt() >= 4) {
            return store.markFailed(delivery.id(), expected.incidentVersion(),
                    expected.claimedAttempt(), kind.name());
        }

        Instant retryAt = expected.durableNextAttempt();
        if (kind == DeliveryOutcomeKind.RATE_LIMITED && outcome.retryAfter().isPresent()) {
            Duration requested = outcome.retryAfter().orElseThrow();
            Duration remaining = Duration.between(now, expected.expiresAt());
            if (requested.compareTo(remaining) >= 0) {
                return store.cancelExpired(delivery.id(), expected.incidentVersion(),
                        expected.claimedAttempt(), kind.name());
            }
            Instant providerRetry = now.plus(requested);
            if (providerRetry.isAfter(retryAt)) {
                retryAt = providerRetry;
            }
        }
        if (!retryAt.isBefore(expected.expiresAt())) {
            return store.cancelExpired(delivery.id(), expected.incidentVersion(),
                    expected.claimedAttempt(), kind.name());
        }
        return store.markRetry(delivery.id(), expected.incidentVersion(),
                expected.claimedAttempt(), retryAt, kind.name());
    }

    @Transactional
    public void cancelClaim(DeliveryClaim expected) {
        store.cancel(expected.deliveryId(), expected.incidentVersion(), expected.claimedAttempt());
    }

    @Transactional
    public void deactivateRecipient(DeliveryClaim claim, Instant now) {
        if (claim.recipient() instanceof PushSecret push) {
            store.deactivatePush(
                    push.id(), push.keyVersion(), push.nonce(), push.ciphertext(), now);
        } else if (claim.recipient() instanceof WebhookSecret webhook) {
            store.deactivateWebhook(
                    webhook.id(), webhook.keyVersion(), webhook.nonce(), webhook.ciphertext(), now);
        }
    }

    private CoordinatedRows lockRows(long targetId, java.util.UUID incidentId, long deliveryId) {
        TargetRow target = store.lockTarget(targetId).orElse(null);
        StateRow state = store.lockState(targetId).orElse(null);
        IncidentRow incident = store.lockIncident(incidentId).orElse(null);
        if (incident == null) {
            return null;
        }
        DeliveryRow delivery = store.lockDelivery(deliveryId).orElse(null);
        if (delivery == null) {
            return null;
        }
        return new CoordinatedRows(targetId, target, state, incident, delivery);
    }

    private CoordinatedRows lockCompletionRows(
            long targetId,
            java.util.UUID incidentId,
            long deliveryId
    ) {
        TargetRow target = store.lockTarget(targetId).orElse(null);
        StateRow state = store.lockState(targetId).orElse(null);
        IncidentRow incident = store.lockIncident(incidentId).orElse(null);
        if (incident == null || incident.targetId() != targetId) {
            return null;
        }
        DeliveryRow delivery = store.lockDelivery(deliveryId).orElse(null);
        return new CoordinatedRows(targetId, target, state, incident, delivery);
    }

    private CoordinatedRows readRows(long targetId, java.util.UUID incidentId, long deliveryId) {
        TargetRow target = store.readTarget(targetId).orElse(null);
        StateRow state = store.readState(targetId).orElse(null);
        IncidentRow incident = store.readIncident(incidentId).orElse(null);
        DeliveryRow delivery = store.readDelivery(deliveryId).orElse(null);
        if (incident == null || delivery == null) {
            return null;
        }
        return new CoordinatedRows(targetId, target, state, incident, delivery);
    }

    private boolean claimable(CoordinatedRows rows) {
        if (rows.target() == null || rows.state() == null
                || !rows.target().active() || !rows.state().active()) {
            return false;
        }
        DeliveryRow delivery = rows.delivery();
        IncidentRow incident = rows.incident();
        if (delivery.status() != DeliveryStatus.PENDING
                || incident.targetId() != rows.targetId()
                || incident.version() != delivery.incidentVersion()
                || !incident.incidentId().equals(delivery.incidentId())) {
            return false;
        }
        return switch (delivery.type()) {
            case INCIDENT_OPENED, SEVERITY_INCREASED -> "OPEN".equals(incident.status());
            case INCIDENT_RESOLVED -> "RESOLVED".equals(incident.status())
                    && "RECOVERED".equals(incident.resolutionReason());
        };
    }

    private Optional<RecipientSecret> activeRecipient(DeliveryRow delivery, Instant now) {
        if (delivery.channel() == NotificationChannel.WEB_PUSH) {
            if (delivery.pushId() == null || delivery.webhookId() != null) {
                return Optional.empty();
            }
            PushRecipientRow push = store.readPush(delivery.pushId()).orElse(null);
            if (push == null || !push.enabled() || push.deleted() || push.sessionId() == null
                    || (push.expirationTime() != null && push.expirationTime() <= now.toEpochMilli())
                    || !authService.isSessionUsable(push.sessionId(), push.userId())) {
                return Optional.empty();
            }
            return Optional.of(new PushSecret(push.id(), push.keyVersion(),
                    push.nonce(), push.ciphertext()));
        }
        if (delivery.webhookId() == null || delivery.pushId() != null) {
            return Optional.empty();
        }
        WebhookRecipientRow webhook = store.readWebhook(delivery.webhookId()).orElse(null);
        if (webhook == null || !webhook.enabled() || webhook.deleted()) {
            return Optional.empty();
        }
        return Optional.of(new WebhookSecret(webhook.id(), webhook.keyVersion(),
                webhook.nonce(), webhook.ciphertext()));
    }

    private boolean sameClaim(DeliveryClaim expected, DeliveryRow delivery) {
        return delivery.id() == expected.deliveryId()
                && delivery.incidentId().equals(expected.incidentId())
                && delivery.incidentVersion() == expected.incidentVersion()
                && delivery.type() == expected.type()
                && delivery.channel() == expected.channel()
                && trustedRecipient(expected, delivery)
                && delivery.status() == DeliveryStatus.PENDING
                && delivery.attemptCount() == expected.claimedAttempt();
    }

    private boolean recordsOpeningSuccess(DeliveryClaim claim) {
        return claim.type() == NotificationType.INCIDENT_OPENED
                || claim.type() == NotificationType.SEVERITY_INCREASED;
    }

    private boolean trustedRecipient(DeliveryClaim claim, DeliveryRow delivery) {
        boolean typeMatches = switch (claim.channel()) {
            case WEB_PUSH -> claim.recipient() instanceof PushReference
                    || claim.recipient() instanceof PushSecret;
            case SLACK -> claim.recipient() instanceof WebhookReference
                    || claim.recipient() instanceof WebhookSecret;
        };
        if (!typeMatches || claim.recipient().id() < 1) {
            return false;
        }
        if (delivery == null) {
            return true;
        }
        return delivery.incidentId().equals(claim.incidentId())
                && delivery.type() == claim.type()
                && delivery.channel() == claim.channel()
                && (switch (claim.channel()) {
                    case WEB_PUSH -> delivery.pushId() != null
                            && delivery.webhookId() == null
                            && delivery.pushId() == claim.recipient().id();
                    case SLACK -> delivery.webhookId() != null
                            && delivery.pushId() == null
                            && delivery.webhookId() == claim.recipient().id();
                });
    }

    private void reconcileRecovery(CoordinatedRows rows, DeliveryClaim claim, Instant now) {
        IncidentRow incident = rows.incident();
        if (rows.target() == null || rows.state() == null
                || !rows.target().active() || !rows.state().active()
                || !"RESOLVED".equals(incident.status())
                || !"RECOVERED".equals(incident.resolutionReason())
                || incident.resolvedAt() == null
                || !activeClaimRecipient(claim, now)) {
            return;
        }
        Instant expiresAt = incident.resolvedAt().plus(DELIVERY_WINDOW);
        if (!now.isBefore(expiresAt)) {
            return;
        }
        store.createRecoveryDelivery(
                incident.incidentId(),
                incident.version(),
                claim.channel(),
                claim.recipient().id(),
                incident.resolvedAt(),
                expiresAt,
                now);
    }

    private boolean activeClaimRecipient(DeliveryClaim claim, Instant now) {
        if (claim.channel() == NotificationChannel.WEB_PUSH) {
            PushRecipientRow push = store.readPush(claim.recipient().id()).orElse(null);
            return push != null && push.enabled() && !push.deleted() && push.sessionId() != null
                    && (push.expirationTime() == null || push.expirationTime() > now.toEpochMilli())
                    && authService.isSessionUsable(push.sessionId(), push.userId());
        }
        WebhookRecipientRow webhook = store.readWebhook(claim.recipient().id()).orElse(null);
        return webhook != null && webhook.enabled() && !webhook.deleted();
    }

    private Optional<RecipientData> recipientReference(DeliveryRow delivery) {
        if (delivery.channel() == NotificationChannel.WEB_PUSH
                && delivery.pushId() != null && delivery.webhookId() == null) {
            return Optional.of(new PushReference(delivery.pushId()));
        }
        if (delivery.channel() == NotificationChannel.SLACK
                && delivery.webhookId() != null && delivery.pushId() == null) {
            return Optional.of(new WebhookReference(delivery.webhookId()));
        }
        return Optional.empty();
    }

    private void cancelIfPending(DeliveryRow delivery) {
        if (delivery.status() == DeliveryStatus.PENDING) {
            store.cancel(delivery.id(), delivery.incidentVersion(), null);
        }
    }

    private void expire(DeliveryRow delivery) {
        store.cancelExpired(delivery.id(), delivery.incidentVersion(), delivery.attemptCount(),
                delivery.lastErrorCode());
    }

    private String priorError(DeliveryRow delivery) {
        return delivery.lastErrorCode() == null ? DeliveryOutcomeKind.PROVIDER_ERROR.name()
                : delivery.lastErrorCode();
    }

    private Instant nextAttempt(Instant expiresAt, int claimedAttempt) {
        Instant eligibleAt = expiresAt.minus(DELIVERY_WINDOW);
        return switch (claimedAttempt) {
            case 1 -> eligibleAt.plusSeconds(5);
            case 2 -> eligibleAt.plusSeconds(30);
            case 3 -> eligibleAt.plusSeconds(120);
            case 4 -> expiresAt;
            default -> throw new IllegalArgumentException("Unsupported notification attempt.");
        };
    }

    private Instant occurredAt(IncidentRow incident, NotificationType type) {
        return switch (type) {
            case INCIDENT_OPENED, SEVERITY_INCREASED -> incident.openedAt();
            case INCIDENT_RESOLVED -> incident.resolvedAt();
        };
    }

    private record CoordinatedRows(
            long targetId,
            TargetRow target,
            StateRow state,
            IncidentRow incident,
            DeliveryRow delivery
    ) { }

    public sealed interface RecipientData permits PushReference, WebhookReference, RecipientSecret {
        long id();
    }

    public record PushReference(long id) implements RecipientData { }

    public record WebhookReference(long id) implements RecipientData { }

    public sealed interface RecipientSecret extends RecipientData permits PushSecret, WebhookSecret {
        int keyVersion();
        byte[] nonce();
        byte[] ciphertext();
    }

    public record PushSecret(long id, int keyVersion, byte[] nonce, byte[] ciphertext)
            implements RecipientSecret {
        public PushSecret {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public String toString() { return "PushSecret[id=" + id + ", redacted]"; }
    }

    public record WebhookSecret(long id, int keyVersion, byte[] nonce, byte[] ciphertext)
            implements RecipientSecret {
        public WebhookSecret {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public String toString() { return "WebhookSecret[id=" + id + ", redacted]"; }
    }

    public record DeliveryClaim(
            long deliveryId,
            long targetId,
            java.util.UUID incidentId,
            long incidentVersion,
            NotificationType type,
            NotificationChannel channel,
            int claimedAttempt,
            Instant durableNextAttempt,
            Instant expiresAt,
            String databaseName,
            String ruleId,
            String severity,
            Instant occurredAt,
            RecipientData recipient
    ) { }
}
