package com.example.monitoring.risk.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Incident(
        UUID incidentId,
        long databaseConfigId,
        String databaseName,
        RuleId ruleId,
        RuleType ruleType,
        IncidentSeverity severity,
        IncidentStatus status,
        Instant openedAt,
        Instant lastObservedAt,
        Instant resolvedAt,
        ResolutionReason resolutionReason,
        String metricName,
        BigDecimal metricValue,
        BigDecimal thresholdValue,
        Long sourceMetricId,
        String message,
        long incidentVersion
) {
    public Incident {
        Objects.requireNonNull(incidentId, "incidentId");
        ContractChecks.safeId(databaseConfigId, "databaseConfigId");
        ContractChecks.safeId(incidentVersion, "incidentVersion");
        ContractChecks.nullableSafeId(sourceMetricId, "sourceMetricId");
        databaseName = ContractChecks.text(databaseName, "databaseName", 100);
        metricName = ContractChecks.text(metricName, "metricName", 100);
        message = ContractChecks.text(message, "message", Integer.MAX_VALUE);
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ruleType, "ruleType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(status, "status");
        openedAt = ContractChecks.millis(openedAt, "openedAt");
        lastObservedAt = ContractChecks.millis(lastObservedAt, "lastObservedAt");
        resolvedAt = ContractChecks.nullableMillis(resolvedAt);
        metricValue = ContractChecks.finiteNonNegative(metricValue, "metricValue");
        thresholdValue = ContractChecks.finiteNonNegative(thresholdValue, "thresholdValue");
        if (lastObservedAt.isBefore(openedAt)) {
            throw new IllegalArgumentException("lastObservedAt cannot be before openedAt");
        }
        if (status == IncidentStatus.OPEN && (resolvedAt != null || resolutionReason != null)) {
            throw new IllegalArgumentException("OPEN incident cannot have resolution fields");
        }
        if (status == IncidentStatus.RESOLVED && (resolvedAt == null || resolutionReason == null)) {
            throw new IllegalArgumentException("RESOLVED incident requires resolution fields");
        }
        if (resolvedAt != null && resolvedAt.isBefore(lastObservedAt)) {
            throw new IllegalArgumentException("resolvedAt cannot be before lastObservedAt");
        }
    }
}
