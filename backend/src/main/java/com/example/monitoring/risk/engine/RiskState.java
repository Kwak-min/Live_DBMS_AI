package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.ConnectionStatus;
import com.example.monitoring.risk.contract.DataFreshness;
import com.example.monitoring.risk.contract.RiskLevel;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record RiskState(
        long databaseConfigId,
        long configVersion,
        long stateVersion,
        boolean enabled,
        boolean deleted,
        ConnectionStatus connectionStatus,
        DataFreshness dataFreshness,
        RiskLevel riskLevel,
        Instant activationAt,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        Long latestMetricId,
        Instant updatedAt
) {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public RiskState {
        safe(databaseConfigId, "databaseConfigId");
        safe(configVersion, "configVersion");
        safe(stateVersion, "stateVersion");
        if (latestMetricId != null) {
            safe(latestMetricId, "latestMetricId");
        }
        Objects.requireNonNull(connectionStatus, "connectionStatus");
        Objects.requireNonNull(dataFreshness, "dataFreshness");
        activationAt = millis(activationAt);
        lastAttemptAt = millis(lastAttemptAt);
        lastSuccessAt = millis(lastSuccessAt);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt").truncatedTo(ChronoUnit.MILLIS);
        if ((!enabled || deleted) && (dataFreshness != DataFreshness.PAUSED || riskLevel != null)) {
            throw new IllegalArgumentException("Disabled or deleted risk state must be PAUSED with null risk");
        }
        if (enabled && !deleted && activationAt == null) {
            throw new IllegalArgumentException("Active risk state requires activationAt");
        }
    }

    public RiskState paused(Instant at) {
        return new RiskState(
                databaseConfigId, configVersion, stateVersion, false, deleted,
                ConnectionStatus.UNKNOWN, DataFreshness.PAUSED, null, null,
                lastAttemptAt, lastSuccessAt, latestMetricId, at);
    }

    static long increment(long value, String field) {
        safe(value, field);
        if (value == MAX_SAFE_INTEGER) {
            throw new IllegalStateException(field + " cannot be incremented safely");
        }
        return value + 1;
    }

    private static void safe(long value, String field) {
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " must be a positive JavaScript-safe integer");
        }
    }

    private static Instant millis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
