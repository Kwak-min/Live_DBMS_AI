package com.example.monitoring.realtime.status;

import com.example.monitoring.risk.contract.StatusSnapshot;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeStatusMessage(
        int schemaVersion,
        UUID eventId,
        String eventType,
        long databaseConfigId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant publishedAt,
        StatusSnapshot data
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "MonitoringStatusChanged";

    public RealtimeStatusMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(data, "data");
    }

    public static RealtimeStatusMessage from(StatusStreamEvent event) {
        Objects.requireNonNull(event, "event");
        return new RealtimeStatusMessage(
                SCHEMA_VERSION,
                event.eventId(),
                EVENT_TYPE,
                event.status().databaseConfigId(),
                event.publishedAt(),
                event.status());
    }
}
