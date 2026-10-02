package com.example.monitoring.notification.stream;

import com.example.monitoring.risk.contract.IncidentEventPayload;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record NotificationIncidentEvent(
        UUID eventId,
        Type eventType,
        Instant publishedAt,
        IncidentEventPayload incident
) {
    public NotificationIncidentEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt")
                .truncatedTo(ChronoUnit.MILLIS);
        Objects.requireNonNull(incident, "incident");
    }

    public enum Type {
        CREATED,
        UPDATED,
        RESOLVED
    }
}
