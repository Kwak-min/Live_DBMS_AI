package com.example.monitoring.risk.persistence;

import com.example.monitoring.common.outbox.OutboxEventType;
import com.example.monitoring.common.outbox.OutboxWriter;
import com.example.monitoring.risk.contract.IncidentEventPayload;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.StatusSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Component
public class RiskOutboxAppender {

    private final OutboxWriter outboxWriter;

    public RiskOutboxAppender(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendIncident(
            UUID eventId,
            OutboxEventType eventType,
            IncidentEventPayload payload
    ) {
        Objects.requireNonNull(payload, "payload");
        requireMatchingIncidentType(eventType, payload);
        outboxWriter.append(
                eventId,
                eventType,
                orderingKey(payload.databaseConfigId()),
                payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendStatus(UUID eventId, StatusSnapshot payload) {
        Objects.requireNonNull(payload, "payload");
        outboxWriter.append(
                eventId,
                OutboxEventType.MONITORING_STATUS_CHANGED,
                orderingKey(payload.databaseConfigId()),
                payload);
    }

    private void requireMatchingIncidentType(
            OutboxEventType eventType,
            IncidentEventPayload payload
    ) {
        boolean matches = switch (Objects.requireNonNull(eventType, "eventType")) {
            case INCIDENT_CREATED -> payload.status() == IncidentStatus.OPEN
                    && payload.severityTransition() == null;
            case INCIDENT_UPDATED -> payload.status() == IncidentStatus.OPEN
                    && payload.severityTransition() != null;
            case INCIDENT_RESOLVED -> payload.status() == IncidentStatus.RESOLVED
                    && payload.severityTransition() == null;
            default -> false;
        };
        if (!matches) {
            throw new IllegalArgumentException("Incident payload does not match outbox event type");
        }
    }

    private String orderingKey(long databaseConfigId) {
        return "database:" + databaseConfigId;
    }
}
