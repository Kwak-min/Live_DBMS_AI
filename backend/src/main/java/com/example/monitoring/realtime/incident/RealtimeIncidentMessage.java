package com.example.monitoring.realtime.incident;

import com.example.monitoring.risk.contract.Incident;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeIncidentMessage(
        int schemaVersion,
        UUID eventId,
        String eventType,
        long databaseConfigId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant publishedAt,
        Incident data
) {
    public static final int SCHEMA_VERSION = 1;

    public RealtimeIncidentMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(data, "data");
    }

    public static RealtimeIncidentMessage from(IncidentStreamEvent event) {
        Objects.requireNonNull(event, "event");
        return new RealtimeIncidentMessage(
                SCHEMA_VERSION,
                event.eventId(),
                event.eventType(),
                event.incident().databaseConfigId(),
                event.publishedAt(),
                event.incident());
    }
}
