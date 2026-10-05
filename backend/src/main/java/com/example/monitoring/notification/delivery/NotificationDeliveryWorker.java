package com.example.monitoring.notification.delivery;

import com.example.monitoring.notification.delivery.NotificationDeliveryStore.DueCandidate;
import com.example.monitoring.notification.delivery.NotificationDeliveryTransaction.DeliveryClaim;
import com.example.monitoring.notification.delivery.NotificationDeliveryTransaction.PushSecret;
import com.example.monitoring.notification.delivery.NotificationDeliveryTransaction.WebhookSecret;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PushSecretBundle;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.notification.slack.SlackMessage;
import com.example.monitoring.notification.slack.SlackSender;
import com.example.monitoring.notification.transport.DeliveryOutcome;
import com.example.monitoring.notification.transport.DeliveryOutcomeKind;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.notification.webpush.WebPushMessage;
import com.example.monitoring.notification.webpush.WebPushRecipient;
import com.example.monitoring.notification.webpush.WebPushSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "monitoring.notifications", name = "enabled", havingValue = "true")
public final class NotificationDeliveryWorker {

    private static final int BATCH_SIZE = 50;
    private static final AtomicBoolean JVM_RUNNING = new AtomicBoolean();

    private final PostgresDeliveryLease lease;
    private final NotificationDeliveryTransaction transaction;
    private final NotificationSecretCodec secrets;
    private final WebPushSender webPushSender;
    private final SlackSender slackSender;
    private final SlackWebhookPolicy slackPolicy;
    private final SlackAttemptPacer slackPacer;
    private final Clock clock;

    public NotificationDeliveryWorker(
            PostgresDeliveryLease lease,
            NotificationDeliveryTransaction transaction,
            NotificationSecretCodec secrets,
            WebPushSender webPushSender,
            SlackSender slackSender,
            SlackWebhookPolicy slackPolicy,
            SlackAttemptPacer slackPacer,
            Clock clock
    ) {
        this.lease = lease;
        this.transaction = transaction;
        this.secrets = secrets;
        this.webPushSender = webPushSender;
        this.slackSender = slackSender;
        this.slackPolicy = slackPolicy;
        this.slackPacer = slackPacer;
        this.clock = clock;
    }

    public int runOnce() {
        if (!JVM_RUNNING.compareAndSet(false, true)) {
            return 0;
        }
        try {
            if (!lease.tryAcquire() || !lease.verifyHeld()) {
                return 0;
            }
            Instant now = now();
            List<DueCandidate> candidates = transaction.dueCandidates(now, BATCH_SIZE);
            int externalAttempts = 0;
            for (DueCandidate candidate : candidates) {
                if (!lease.verifyHeld()) {
                    break;
                }
                DeliveryClaim claim = transaction.claim(candidate, now()).orElse(null);
                if (claim == null) {
                    continue;
                }
                DeliveryResult result = deliver(claim);
                if (result.externalAttempt()) {
                    externalAttempts++;
                }
                if (!result.continueProcessing()) {
                    break;
                }
            }
            return externalAttempts;
        } finally {
            JVM_RUNNING.set(false);
        }
    }

    private DeliveryResult deliver(DeliveryClaim claim) {
        if (claim.channel() == com.example.monitoring.notification.api.NotificationChannel.WEB_PUSH) {
            return deliverPush(claim);
        }
        if (claim.channel() == com.example.monitoring.notification.api.NotificationChannel.SLACK) {
            return deliverSlack(claim);
        }
        transaction.complete(claim, DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED), now());
        return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
    }

    private DeliveryResult deliverPush(DeliveryClaim claim) {
        if (!lease.verifyHeld()) {
            return DeliveryResult.STOP_WITHOUT_ATTEMPT;
        }
        DeliveryClaim current = transaction.revalidate(claim, now()).orElse(null);
        if (current == null) {
            transaction.cancelClaim(claim);
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        PushSecret encrypted = (PushSecret) current.recipient();
        final WebPushRecipient recipient;
        final WebPushMessage message;
        try {
            PushSecretBundle bundle = secrets.decryptPush(
                    encrypted.id(), encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
            recipient = new WebPushRecipient(bundle.endpoint(), bundle.p256dh(), bundle.auth());
            message = new WebPushMessage(
                    current.deliveryId(), current.incidentId(), current.type(),
                    pushTitle(current.type()), pushBody(current), now());
        } catch (RuntimeException exception) {
            transaction.complete(current, DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED), now());
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        if (!lease.verifyHeld()) {
            return DeliveryResult.STOP_WITHOUT_ATTEMPT;
        }
        DeliveryOutcome outcome;
        try {
            outcome = Objects.requireNonNull(webPushSender.send(recipient, message));
        } catch (RuntimeException exception) {
            outcome = DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR);
        }
        if (!lease.verifyHeld()) {
            persistKnownSuccess(current, outcome);
            return DeliveryResult.STOP_AFTER_ATTEMPT;
        }
        boolean completed = transaction.complete(current, outcome, now());
        if (completed && outcome.kind() == DeliveryOutcomeKind.RECIPIENT_GONE) {
            transaction.deactivateRecipient(current, now());
        }
        return DeliveryResult.CONTINUE_AFTER_ATTEMPT;
    }

    private DeliveryResult deliverSlack(DeliveryClaim claim) {
        DeliveryClaim pacedClaim = transaction.revalidate(claim, now()).orElse(null);
        if (pacedClaim == null) {
            transaction.cancelClaim(claim);
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        WebhookSecret encrypted = (WebhookSecret) pacedClaim.recipient();
        final String initialUrl;
        final String pacingIdentity;
        try {
            initialUrl = secrets.decryptSlack(
                    encrypted.id(), encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
            pacingIdentity = slackPolicy.canonicalIdentity(initialUrl);
        } catch (RuntimeException exception) {
            transaction.complete(pacedClaim, DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED), now());
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        if (!slackPacer.awaitPermit(pacingIdentity, lease.acquiredNanoTime()) || !lease.verifyHeld()) {
            return DeliveryResult.STOP_WITHOUT_ATTEMPT;
        }
        DeliveryClaim current = transaction.revalidate(claim, now()).orElse(null);
        if (current == null) {
            transaction.cancelClaim(claim);
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        WebhookSecret currentSecret = (WebhookSecret) current.recipient();
        final String currentUrl;
        try {
            currentUrl = secrets.decryptSlack(
                    currentSecret.id(), currentSecret.keyVersion(),
                    currentSecret.nonce(), currentSecret.ciphertext());
            if (!pacingIdentity.equals(slackPolicy.canonicalIdentity(currentUrl))) {
                return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
            }
        } catch (RuntimeException exception) {
            transaction.complete(current, DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED), now());
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        if (!lease.verifyHeld()) {
            return DeliveryResult.STOP_WITHOUT_ATTEMPT;
        }
        final SlackMessage message;
        try {
            message = new SlackMessage(
                    current.severity(), current.databaseName(), current.ruleId(), current.type(),
                    current.occurredAt(), current.incidentId());
        } catch (RuntimeException exception) {
            transaction.complete(current, DeliveryOutcome.of(DeliveryOutcomeKind.REJECTED), now());
            return DeliveryResult.CONTINUE_WITHOUT_ATTEMPT;
        }
        DeliveryOutcome outcome;
        try {
            outcome = Objects.requireNonNull(slackSender.send(currentUrl, message));
        } catch (RuntimeException exception) {
            outcome = DeliveryOutcome.of(DeliveryOutcomeKind.PROVIDER_ERROR);
        } finally {
            slackPacer.recordCompletion(pacingIdentity);
        }
        if (!lease.verifyHeld()) {
            persistKnownSuccess(current, outcome);
            return DeliveryResult.STOP_AFTER_ATTEMPT;
        }
        boolean completed = transaction.complete(current, outcome, now());
        if (completed && outcome.kind() == DeliveryOutcomeKind.RECIPIENT_GONE) {
            transaction.deactivateRecipient(current, now());
        }
        return DeliveryResult.CONTINUE_AFTER_ATTEMPT;
    }

    private void persistKnownSuccess(DeliveryClaim claim, DeliveryOutcome outcome) {
        if (outcome.kind() == DeliveryOutcomeKind.SENT) {
            transaction.complete(claim, outcome, now());
        }
    }

    private String pushTitle(NotificationType type) {
        return switch (type) {
            case INCIDENT_OPENED -> "Incident opened";
            case SEVERITY_INCREASED -> "Incident severity increased";
            case INCIDENT_RESOLVED -> "Incident resolved";
        };
    }

    private String pushBody(DeliveryClaim claim) {
        return claim.databaseName() + ": " + claim.severity() + " " + claim.ruleId();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private record DeliveryResult(boolean externalAttempt, boolean continueProcessing) {
        private static final DeliveryResult CONTINUE_WITHOUT_ATTEMPT = new DeliveryResult(false, true);
        private static final DeliveryResult STOP_WITHOUT_ATTEMPT = new DeliveryResult(false, false);
        private static final DeliveryResult CONTINUE_AFTER_ATTEMPT = new DeliveryResult(true, true);
        private static final DeliveryResult STOP_AFTER_ATTEMPT = new DeliveryResult(true, false);
    }
}
