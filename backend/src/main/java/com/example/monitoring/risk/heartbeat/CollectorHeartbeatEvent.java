package com.example.monitoring.risk.heartbeat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record CollectorHeartbeatEvent(
        UUID eventId,
        Instant publishedAt,
        String collectorId,
        Instant timestamp,
        Instant lastCycleStartedAt,
        Instant lastCycleCompletedAt,
        boolean cycleInProgress
) {
    public CollectorHeartbeatEvent {
        Objects.requireNonNull(eventId, "eventId");
        publishedAt = millis(publishedAt, "publishedAt");
        Objects.requireNonNull(collectorId, "collectorId");
        if (collectorId.isBlank()) {
            throw new IllegalArgumentException("collectorId must not be blank");
        }
        timestamp = millis(timestamp, "timestamp");
        lastCycleStartedAt = nullableMillis(lastCycleStartedAt);
        lastCycleCompletedAt = nullableMillis(lastCycleCompletedAt);
    }

    private static Instant millis(Instant value, String field) {
        return Objects.requireNonNull(value, field).truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant nullableMillis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
