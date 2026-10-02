package com.example.monitoring.risk.heartbeat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record HeartbeatHealth(
        String collectorId,
        boolean live,
        Instant receivedAt,
        Instant eventTimestamp,
        Instant lastCycleStartedAt,
        Instant lastCycleCompletedAt,
        boolean cycleInProgress
) {
    public HeartbeatHealth {
        Objects.requireNonNull(collectorId, "collectorId");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt")
                .truncatedTo(ChronoUnit.MILLIS);
        eventTimestamp = Objects.requireNonNull(eventTimestamp, "eventTimestamp")
                .truncatedTo(ChronoUnit.MILLIS);
        lastCycleStartedAt = nullableMillis(lastCycleStartedAt);
        lastCycleCompletedAt = nullableMillis(lastCycleCompletedAt);
    }

    private static Instant nullableMillis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
