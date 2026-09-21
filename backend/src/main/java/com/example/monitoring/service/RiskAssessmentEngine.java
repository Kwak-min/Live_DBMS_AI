package com.example.monitoring.service;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.RiskSeverity;
import com.example.monitoring.domain.RiskThresholdRule;
import com.example.monitoring.dto.IncidentCreatedEvent;
import com.example.monitoring.dto.MetricCollectedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAssessmentEngine {

    private final RiskThresholdRule defaultThresholdRule = new RiskThresholdRule();

    public List<IncidentCreatedEvent> evaluateRisk(MetricCollectedEvent event) {
        return evaluateRisk(event, defaultThresholdRule);
    }

    public List<IncidentCreatedEvent> evaluateRisk(MetricCollectedEvent event, RiskThresholdRule rules) {
        List<IncidentCreatedEvent> incidents = new ArrayList<>();
        LocalDateTime now = event.getTimestamp() != null ? event.getTimestamp() : LocalDateTime.now();

        // 1. Connection Failure Check (FATAL)
        if (event.getCollectionStatus() == CollectionStatus.CONNECTION_FAILED) {
            incidents.add(buildIncident(
                    event,
                    RiskSeverity.FATAL,
                    "CONNECTION_FAILURE",
                    "Database connection failed: " + (event.getErrorMessage() != null ? event.getErrorMessage() : "Unknown error"),
                    "connectionStatus",
                    null,
                    null,
                    now
            ));
            return incidents; // Stop further metric checks if connection failed
        }

        // 2. Active Connections Ratio Check
        if (event.getActiveConnections() != null && event.getMaxConnections() != null && event.getMaxConnections() > 0) {
            double ratio = (double) event.getActiveConnections() / event.getMaxConnections();
            if (ratio >= rules.getConnectionFatalRatio()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.FATAL,
                        "CONNECTION_RATIO_EXCEEDED",
                        String.format("Active connections ratio reached %.2f%% (FATAL)", ratio * 100),
                        "activeConnectionsRatio",
                        ratio,
                        rules.getConnectionFatalRatio(),
                        now
                ));
            } else if (ratio >= rules.getConnectionCriticalRatio()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.CRITICAL,
                        "CONNECTION_RATIO_EXCEEDED",
                        String.format("Active connections ratio reached %.2f%% (CRITICAL)", ratio * 100),
                        "activeConnectionsRatio",
                        ratio,
                        rules.getConnectionCriticalRatio(),
                        now
                ));
            } else if (ratio >= rules.getConnectionWarningRatio()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.WARNING,
                        "CONNECTION_RATIO_EXCEEDED",
                        String.format("Active connections ratio reached %.2f%% (WARNING)", ratio * 100),
                        "activeConnectionsRatio",
                        ratio,
                        rules.getConnectionWarningRatio(),
                        now
                ));
            }
        }

        // 3. CPU Usage Check
        if (event.getCpuUsage() != null) {
            if (event.getCpuUsage() >= rules.getCpuCriticalThreshold()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.CRITICAL,
                        "CPU_USAGE_HIGH",
                        String.format("CPU usage reached %.2f%% (CRITICAL)", event.getCpuUsage()),
                        "cpuUsage",
                        event.getCpuUsage(),
                        rules.getCpuCriticalThreshold(),
                        now
                ));
            } else if (event.getCpuUsage() >= rules.getCpuWarningThreshold()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.WARNING,
                        "CPU_USAGE_HIGH",
                        String.format("CPU usage reached %.2f%% (WARNING)", event.getCpuUsage()),
                        "cpuUsage",
                        event.getCpuUsage(),
                        rules.getCpuWarningThreshold(),
                        now
                ));
            }
        }

        // 4. Memory Usage Check
        if (event.getMemoryUsage() != null) {
            if (event.getMemoryUsage() >= rules.getMemoryCriticalThreshold()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.CRITICAL,
                        "MEMORY_USAGE_HIGH",
                        String.format("Memory usage reached %.2f%% (CRITICAL)", event.getMemoryUsage()),
                        "memoryUsage",
                        event.getMemoryUsage(),
                        rules.getMemoryCriticalThreshold(),
                        now
                ));
            } else if (event.getMemoryUsage() >= rules.getMemoryWarningThreshold()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.WARNING,
                        "MEMORY_USAGE_HIGH",
                        String.format("Memory usage reached %.2f%% (WARNING)", event.getMemoryUsage()),
                        "memoryUsage",
                        event.getMemoryUsage(),
                        rules.getMemoryWarningThreshold(),
                        now
                ));
            }
        }

        // 5. Slow Queries Check
        if (event.getSlowQueries() != null) {
            if (event.getSlowQueries() >= rules.getSlowQueriesCriticalThreshold()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.CRITICAL,
                        "SLOW_QUERIES_HIGH",
                        String.format("Slow queries count reached %d (CRITICAL)", event.getSlowQueries()),
                        "slowQueries",
                        event.getSlowQueries().doubleValue(),
                        (double) rules.getSlowQueriesCriticalThreshold(),
                        now
                ));
            } else if (event.getSlowQueries() >= rules.getSlowQueriesWarningThreshold()) {
                incidents.add(buildIncident(
                        event,
                        RiskSeverity.WARNING,
                        "SLOW_QUERIES_HIGH",
                        String.format("Slow queries count reached %d (WARNING)", event.getSlowQueries()),
                        "slowQueries",
                        event.getSlowQueries().doubleValue(),
                        (double) rules.getSlowQueriesWarningThreshold(),
                        now
                ));
            }
        }

        return incidents;
    }

    private IncidentCreatedEvent buildIncident(MetricCollectedEvent event,
                                               RiskSeverity severity,
                                               String ruleType,
                                               String message,
                                               String metricName,
                                               Double metricValue,
                                               Double thresholdValue,
                                               LocalDateTime timestamp) {
        return IncidentCreatedEvent.builder()
                .incidentId(UUID.randomUUID().toString())
                .databaseConfigId(event.getDatabaseConfigId())
                .databaseName(event.getDatabaseName())
                .severity(severity)
                .ruleType(ruleType)
                .message(message)
                .metricName(metricName)
                .metricValue(metricValue)
                .thresholdValue(thresholdValue)
                .timestamp(timestamp)
                .build();
    }
}
