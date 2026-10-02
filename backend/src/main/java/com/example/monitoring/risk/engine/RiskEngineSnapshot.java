package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RuleId;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record RiskEngineSnapshot(
        RiskPolicy policy,
        RiskState state,
        Map<RuleId, RuleClock> ruleClocks,
        Map<RuleId, Incident> openIncidents
) {
    public RiskEngineSnapshot {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(state, "state");
        ruleClocks = immutableEnumMap(ruleClocks);
        openIncidents = immutableEnumMap(openIncidents);
    }

    private static <T> Map<RuleId, T> immutableEnumMap(Map<RuleId, T> source) {
        EnumMap<RuleId, T> copy = new EnumMap<>(RuleId.class);
        copy.putAll(Objects.requireNonNull(source, "source"));
        return Collections.unmodifiableMap(copy);
    }
}
