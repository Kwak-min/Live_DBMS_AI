package com.example.monitoring.lifecycle.adapter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record LockedTarget(long configVersion, boolean enabled, String name, boolean deleted) {
}

record LockedMonitoringState(long configVersion, long stateVersion, boolean enabled, boolean deleted) {
}

record LockedIncident(
        UUID incidentId,
        long databaseConfigId,
        String databaseName,
        String ruleId,
        String ruleType,
        String severity,
        Instant openedAt,
        Instant lastObservedAt,
        String metricName,
        BigDecimal metricValue,
        BigDecimal thresholdValue,
        Long sourceMetricId,
        String message,
        long incidentVersion
) {
}

record MonitoringStateWrite(
        long databaseConfigId,
        long configVersion,
        long stateVersion,
        boolean enabled,
        boolean deleted,
        String dataFreshness,
        Instant activationAt,
        Instant updatedAt
) {
}

record IncidentResolution(
        LockedIncident incident,
        long nextIncidentVersion,
        String reason,
        Instant resolvedAt
) {
}

record SerializedLifecycleEvent(
        UUID eventId,
        String eventType,
        Instant publishedAt,
        String json
) {
}
