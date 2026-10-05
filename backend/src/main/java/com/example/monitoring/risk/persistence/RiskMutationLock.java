package com.example.monitoring.risk.persistence;

import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.engine.RiskEngineSnapshot;
import com.example.monitoring.risk.engine.RiskState;
import com.example.monitoring.risk.engine.RuleClock;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record RiskMutationLock(
        LockedTarget target,
        RiskState state,
        RiskPolicy policy,
        Map<RuleId, RuleClock> ruleClocks,
        List<Incident> openIncidents,
        List<PendingDelivery> pendingDeliveries
) {
    public RiskMutationLock {
        EnumMap<RuleId, RuleClock> clocks = new EnumMap<>(RuleId.class);
        clocks.putAll(ruleClocks);
        ruleClocks = Map.copyOf(clocks);
        openIncidents = List.copyOf(openIncidents);
        pendingDeliveries = List.copyOf(pendingDeliveries);
    }

    public RiskEngineSnapshot engineSnapshot() {
        EnumMap<RuleId, Incident> incidentsByRule = new EnumMap<>(RuleId.class);
        for (Incident incident : openIncidents) {
            incidentsByRule.put(incident.ruleId(), incident);
        }
        return new RiskEngineSnapshot(policy, state, ruleClocks, incidentsByRule);
    }
}
