package com.example.monitoring.risk.contract;

public enum IncidentSeverity {
    WARNING,
    CRITICAL,
    FATAL;

    public RiskLevel asRiskLevel() {
        return RiskLevel.valueOf(name());
    }
}
