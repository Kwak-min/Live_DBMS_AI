package com.example.monitoring.risk.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record MetricRiskObservation(
        long metricId,
        UUID sourceEventId,
        Instant observedAt,
        Instant evaluatedAt,
        CollectionOutcome outcome,
        BigDecimal activeConnectionsRatio,
        BigDecimal slowQueriesPerSecond
) implements RiskObservation {
    public MetricRiskObservation {
        if (metricId < 1 || metricId > RiskState.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("metricId must be a positive JavaScript-safe integer");
        }
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        observedAt = Objects.requireNonNull(observedAt, "observedAt").truncatedTo(ChronoUnit.MILLIS);
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt").truncatedTo(ChronoUnit.MILLIS);
        Objects.requireNonNull(outcome, "outcome");
        requireNonNegative(activeConnectionsRatio, "activeConnectionsRatio");
        requireNonNegative(slowQueriesPerSecond, "slowQueriesPerSecond");
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
