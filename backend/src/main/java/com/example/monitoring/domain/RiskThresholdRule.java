package com.example.monitoring.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskThresholdRule {

    @Builder.Default
    private double cpuWarningThreshold = 80.0;
    @Builder.Default
    private double cpuCriticalThreshold = 90.0;

    @Builder.Default
    private double memoryWarningThreshold = 85.0;
    @Builder.Default
    private double memoryCriticalThreshold = 95.0;

    @Builder.Default
    private double connectionWarningRatio = 0.80; // 80% of max_connections
    @Builder.Default
    private double connectionCriticalRatio = 0.90; // 90% of max_connections
    @Builder.Default
    private double connectionFatalRatio = 0.95; // 95% of max_connections

    @Builder.Default
    private long slowQueriesWarningThreshold = 10L;
    @Builder.Default
    private long slowQueriesCriticalThreshold = 50L;
}
