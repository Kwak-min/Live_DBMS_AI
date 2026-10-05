package com.example.monitoring.risk.contract;

import java.math.BigDecimal;
import java.util.Objects;

public record RiskRule(
        RuleId ruleId,
        String metricName,
        RuleOperator operator,
        BigDecimal warningThreshold,
        BigDecimal criticalThreshold,
        BigDecimal fatalThreshold,
        int sustainSeconds,
        int recoverySeconds,
        boolean enabled
) {
    public RiskRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(operator, "operator");
        metricName = ContractChecks.text(metricName, "metricName", 100);
        if (!ruleId.configurable()) {
            throw new IllegalArgumentException("Only configurable rules belong in RiskPolicy");
        }
        if (operator != RuleOperator.GTE) {
            throw new IllegalArgumentException("Only GTE rules are supported");
        }
        requireDuration(sustainSeconds, "sustainSeconds");
        requireDuration(recoverySeconds, "recoverySeconds");
        requirePositive(warningThreshold, "warningThreshold");
        requirePositive(criticalThreshold, "criticalThreshold");
        if (warningThreshold.compareTo(criticalThreshold) >= 0) {
            throw new IllegalArgumentException("warningThreshold must be below criticalThreshold");
        }
        if (ruleId == RuleId.CONNECTION_RATIO) {
            if (!"activeConnectionsRatio".equals(metricName)) {
                throw new IllegalArgumentException("CONNECTION_RATIO metricName is fixed");
            }
            requirePositive(fatalThreshold, "fatalThreshold");
            if (criticalThreshold.compareTo(fatalThreshold) >= 0
                    || fatalThreshold.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("CONNECTION_RATIO thresholds are invalid");
            }
        } else {
            if (!"slowQueriesPerSecond".equals(metricName)) {
                throw new IllegalArgumentException("SLOW_QUERY_RATE metricName is fixed");
            }
            if (fatalThreshold != null) {
                throw new IllegalArgumentException("SLOW_QUERY_RATE fatalThreshold must be null");
            }
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireDuration(int value, String field) {
        if (value < 5 || value > 300 || value % 5 != 0) {
            throw new IllegalArgumentException(field + " must be 5..300 in five-second steps");
        }
    }
}
