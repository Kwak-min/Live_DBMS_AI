package com.example.monitoring.risk.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IncidentEventPayload(
        Instant timestamp,
        UUID sourceEventId,
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
        long incidentVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) SeverityTransition severityTransition
) {
    public IncidentEventPayload {
        timestamp = ContractChecks.millis(timestamp, "timestamp");
    }

    public static IncidentEventPayload created(Incident incident, Instant timestamp, UUID sourceEventId) {
        return from(incident, timestamp, sourceEventId, null);
    }

    public static IncidentEventPayload updated(
            Incident incident,
            Instant timestamp,
            UUID sourceEventId,
            SeverityTransition severityTransition
    ) {
        return from(incident, timestamp, sourceEventId,
                java.util.Objects.requireNonNull(severityTransition, "severityTransition"));
    }

    public static IncidentEventPayload resolved(Incident incident, Instant timestamp, UUID sourceEventId) {
        return from(incident, timestamp, sourceEventId, null);
    }

    private static IncidentEventPayload from(
            Incident incident,
            Instant timestamp,
            UUID sourceEventId,
            SeverityTransition severityTransition
    ) {
        java.util.Objects.requireNonNull(incident, "incident");
        return new IncidentEventPayload(
                timestamp,
                sourceEventId,
                incident.incidentId(),
                incident.databaseConfigId(),
                incident.databaseName(),
                incident.ruleId(),
                incident.ruleType(),
                incident.severity(),
                incident.status(),
                incident.openedAt(),
                incident.lastObservedAt(),
                incident.resolvedAt(),
                incident.resolutionReason(),
                incident.metricName(),
                incident.metricValue(),
                incident.thresholdValue(),
                incident.sourceMetricId(),
                incident.message(),
                incident.incidentVersion(),
                severityTransition);
    }
}
