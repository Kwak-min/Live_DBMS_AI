package com.example.monitoring.risk.engine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record StaleDueObservation(Instant dueAt, Instant scannedAt) implements RiskObservation {
    public StaleDueObservation {
        dueAt = Objects.requireNonNull(dueAt, "dueAt").truncatedTo(ChronoUnit.MILLIS);
        scannedAt = Objects.requireNonNull(scannedAt, "scannedAt").truncatedTo(ChronoUnit.MILLIS);
        if (scannedAt.isBefore(dueAt)) {
            throw new IllegalArgumentException("stale scan cannot precede dueAt");
        }
    }
}
