package com.example.monitoring.risk.engine;

public sealed interface RiskObservation permits MetricRiskObservation, StaleDueObservation {
}
