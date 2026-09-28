package com.example.monitoring.dto;

import com.example.monitoring.domain.RiskSeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentCreatedEvent {
    private String incidentId;
    private Long databaseConfigId;
    private String databaseName;
    private RiskSeverity severity;
    private String ruleType;
    private String message;
    private String metricName;
    private Double metricValue;
    private Double thresholdValue;
    private LocalDateTime timestamp;
}
