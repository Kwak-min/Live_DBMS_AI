package com.example.monitoring.risk.contract;

import java.time.Instant;
import java.util.List;

public record RiskPolicy(
        long databaseConfigId,
        long version,
        int staleAfterSeconds,
        int notificationCooldownSeconds,
        List<RiskRule> rules,
        Instant updatedAt
) {
    public RiskPolicy {
        ContractChecks.safeId(databaseConfigId, "databaseConfigId");
        ContractChecks.safeId(version, "version");
        if (staleAfterSeconds < 30 || staleAfterSeconds > 300) {
            throw new IllegalArgumentException("staleAfterSeconds must be 30..300");
        }
        if (notificationCooldownSeconds < 60 || notificationCooldownSeconds > 3600) {
            throw new IllegalArgumentException("notificationCooldownSeconds must be 60..3600");
        }
        rules = MonitoringContracts.canonicalRules(rules);
        updatedAt = ContractChecks.millis(updatedAt, "updatedAt");
    }
}
