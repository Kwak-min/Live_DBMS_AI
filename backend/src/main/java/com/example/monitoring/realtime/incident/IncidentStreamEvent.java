package com.example.monitoring.realtime.incident;

import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.SeverityTransition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IncidentStreamEvent(
        UUID eventId,
        String eventType,
        Instant publishedAt,
        Instant timestamp,
        UUID sourceEventId,
        Incident incident,
        SeverityTransition severityTransition
) {
    public IncidentStreamEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(incident, "incident");
    }
}
