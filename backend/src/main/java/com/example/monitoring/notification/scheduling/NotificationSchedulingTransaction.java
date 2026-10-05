package com.example.monitoring.notification.scheduling;

import com.example.monitoring.common.outbox.ProcessedEventStore;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore.IncidentRow;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore.PendingDelivery;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore.PolicyRow;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore.Recipient;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore.StateRow;
import com.example.monitoring.notification.scheduling.NotificationSchedulingStore.TargetRow;
import com.example.monitoring.notification.stream.NotificationIncidentEvent;
import com.example.monitoring.notification.transport.NotificationType;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.SeverityTransition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
public class NotificationSchedulingTransaction {

    public static final String CONSUMER_GROUP = "cg:notification";
    private static final long DELIVERY_WINDOW_SECONDS = 600;

    private final NotificationSchedulingStore store;
    private final ProcessedEventStore processedEvents;
    private final Clock clock;

    public NotificationSchedulingTransaction(
            NotificationSchedulingStore store,
            ProcessedEventStore processedEvents,
            Clock clock
    ) {
        this.store = store;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Transactional
    public void process(String sourceStream, NotificationIncidentEvent event) {
        Objects.requireNonNull(sourceStream, "sourceStream");
        Objects.requireNonNull(event, "event");
        if (!processedEvents.markProcessed(sourceStream, CONSUMER_GROUP, event.eventId())) {
            return;
        }

        IncidentEventPayload payload = event.incident();
        TargetRow target = store.lockTarget(payload.databaseConfigId()).orElse(null);
        if (target == null) {
            return;
        }
        StateRow state = store.lockState(payload.databaseConfigId(), event.eventId());
        PolicyRow policy = store.lockPolicy(payload.databaseConfigId(), event.eventId());
        IncidentRow current = store.lockIncident(payload.incidentId()).orElse(null);
        if (current == null) {
            return;
        }
        store.lockDeliveries(payload.incidentId());
        if (!matchesCurrent(payload, current)) {
            return;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        boolean targetActive = target.active() && state.active();
        switch (event.eventType()) {
            case CREATED -> {
                if (targetActive) {
                    createForAllActive(
                            payload,
                            NotificationType.INCIDENT_OPENED,
                            payload.timestamp(),
                            now);
                }
            }
            case UPDATED -> handleUpdate(payload, policy, targetActive, now);
            case RESOLVED -> handleResolution(payload, targetActive, now);
        }
    }

    private void handleUpdate(
            IncidentEventPayload payload,
            PolicyRow policy,
            boolean targetActive,
            Instant now
    ) {
        if (payload.severityTransition() == SeverityTransition.DECREASED) {
            store.cancelPendingIncreases(payload.incidentId());
            return;
        }
        if (!targetActive) {
            store.cancelPendingIncreases(payload.incidentId());
            return;
        }
        if (payload.severity() == IncidentSeverity.FATAL) {
            store.cancelPendingIncreases(payload.incidentId());
            createForAllActive(
                    payload,
                    NotificationType.SEVERITY_INCREASED,
                    payload.timestamp(),
                    now);
            return;
        }

        List<Recipient> recipients = store.activeRecipients(now, payload.timestamp());
        for (Recipient recipient : recipients) {
            PendingDelivery pending = store.pendingIncrease(payload.incidentId(), recipient).orElse(null);
            if (pending != null && pending.expiresAt().isAfter(now)) {
                store.mergePending(pending.id(), payload.incidentVersion());
                continue;
            }
            if (pending != null) {
                store.cancelDelivery(pending.id());
            }
            Instant eligibleAt = store.lastSuccessfulOpenOrIncrease(payload.incidentId(), recipient)
                    .map(sentAt -> later(payload.timestamp(), sentAt.plusSeconds(policy.cooldownSeconds())))
                    .orElse(payload.timestamp());
            create(payload, NotificationType.SEVERITY_INCREASED, recipient, eligibleAt, now);
        }
    }

    private void handleResolution(IncidentEventPayload payload, boolean targetActive, Instant now) {
        store.cancelPendingOpenOrIncrease(payload.incidentId());
        if (!targetActive || payload.resolutionReason() != ResolutionReason.RECOVERED) {
            return;
        }
        for (Recipient recipient : store.activeRecipients(now, payload.timestamp())) {
            if (store.hasSuccessfulOpenOrIncrease(payload.incidentId(), recipient)) {
                create(payload, NotificationType.INCIDENT_RESOLVED, recipient, payload.timestamp(), now);
            }
        }
    }

    private void createForAllActive(
            IncidentEventPayload payload,
            NotificationType type,
            Instant eligibleAt,
            Instant now
    ) {
        for (Recipient recipient : store.activeRecipients(now, payload.timestamp())) {
            create(payload, type, recipient, eligibleAt, now);
        }
    }

    private void create(
            IncidentEventPayload payload,
            NotificationType type,
            Recipient recipient,
            Instant eligibleAt,
            Instant now
    ) {
        Instant expiresAt = eligibleAt.plusSeconds(DELIVERY_WINDOW_SECONDS);
        if (!now.isBefore(expiresAt)) {
            return;
        }
        store.createDelivery(
                payload.incidentId(),
                payload.incidentVersion(),
                type,
                recipient,
                eligibleAt,
                expiresAt,
                now);
    }

    private boolean matchesCurrent(IncidentEventPayload payload, IncidentRow current) {
        return current.databaseConfigId() == payload.databaseConfigId()
                && current.ruleId() == payload.ruleId()
                && current.severity() == payload.severity()
                && current.status() == payload.status()
                && current.resolutionReason() == payload.resolutionReason()
                && current.incidentVersion() == payload.incidentVersion();
    }

    private Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
