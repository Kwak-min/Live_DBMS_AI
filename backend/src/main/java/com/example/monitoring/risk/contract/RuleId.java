package com.example.monitoring.risk.contract;

public enum RuleId {
    CONNECTION_RATIO,
    SLOW_QUERY_RATE,
    CONNECTION_FAILURE,
    COLLECTION_STALE;

    public boolean configurable() {
        return this == CONNECTION_RATIO || this == SLOW_QUERY_RATE;
    }
}
