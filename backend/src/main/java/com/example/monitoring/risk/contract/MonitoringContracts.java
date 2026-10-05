package com.example.monitoring.risk.contract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public final class MonitoringContracts {

    private static final List<RiskRule> DEFAULT_RULES = List.of(
            new RiskRule(
                    RuleId.CONNECTION_RATIO,
                    "activeConnectionsRatio",
                    RuleOperator.GTE,
                    new BigDecimal("0.80"),
                    new BigDecimal("0.90"),
                    new BigDecimal("0.95"),
                    15,
                    15,
                    true),
            new RiskRule(
                    RuleId.SLOW_QUERY_RATE,
                    "slowQueriesPerSecond",
                    RuleOperator.GTE,
                    new BigDecimal("1.0"),
                    new BigDecimal("5.0"),
                    null,
                    15,
                    15,
                    true));

    private MonitoringContracts() {
    }

    public static List<RiskRule> defaultRules() {
        return DEFAULT_RULES;
    }

    static List<RiskRule> canonicalRules(List<RiskRule> rules) {
        if (rules == null || rules.size() != 2) {
            throw new IllegalArgumentException("RiskPolicy requires exactly two rules");
        }
        List<RiskRule> copy = new ArrayList<>(rules);
        if (!EnumSet.copyOf(copy.stream().map(RiskRule::ruleId).toList())
                .equals(EnumSet.of(RuleId.CONNECTION_RATIO, RuleId.SLOW_QUERY_RATE))) {
            throw new IllegalArgumentException("RiskPolicy requires CONNECTION_RATIO and SLOW_QUERY_RATE once each");
        }
        copy.sort(Comparator.comparingInt(rule -> rule.ruleId() == RuleId.CONNECTION_RATIO ? 0 : 1));
        return List.copyOf(copy);
    }
}
