package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.RuleId;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record RiskEvaluation(
        RiskState state,
        Map<RuleId, RuleClock> ruleClocks,
        List<IncidentTransition> incidentTransitions
) {
    public RiskEvaluation {
        EnumMap<RuleId, RuleClock> clocks = new EnumMap<>(RuleId.class);
        clocks.putAll(ruleClocks);
        ruleClocks = Collections.unmodifiableMap(clocks);
        incidentTransitions = List.copyOf(incidentTransitions);
    }
}
