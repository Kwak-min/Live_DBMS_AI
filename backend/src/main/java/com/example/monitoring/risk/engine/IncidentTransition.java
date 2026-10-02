package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.ResolutionReason;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleType;
import com.example.monitoring.risk.contract.SeverityTransition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IncidentTransition(
        IncidentTransitionKind kind,
        UUID incidentId,
        RuleId ruleId,
        RuleType ruleType,
        IncidentSeverity previousSeverity,
        IncidentSeverity severity,
        SeverityTransition severityTransition,
        Instant occurredAt,
        String metricName,
        BigDecimal metricValue,
        BigDecimal thresholdValue,
        Long sourceMetricId,
        UUID sourceEventId,
        String message,
        long nextIncidentVersion,
        ResolutionReason resolutionReason
) {
}
