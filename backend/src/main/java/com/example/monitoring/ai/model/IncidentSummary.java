package com.example.monitoring.ai.model;

import java.time.Instant;

public record IncidentSummary(
        String incidentId,
        String ruleId,
        String severity,
        String status,
        Instant openedAt,
        Instant resolvedAt,
        String metricName,
        Double metricValue,
        Double thresholdValue,
        String message
) {
}
