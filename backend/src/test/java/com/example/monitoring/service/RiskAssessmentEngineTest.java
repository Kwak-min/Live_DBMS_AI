package com.example.monitoring.service;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.RiskSeverity;
import com.example.monitoring.dto.IncidentCreatedEvent;
import com.example.monitoring.dto.MetricCollectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskAssessmentEngineTest {

    private RiskAssessmentEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RiskAssessmentEngine();
    }

    @Test
    @DisplayName("Connection failure triggers FATAL severity incident")
    void connectionFailure_triggersFatalIncident() {
        MetricCollectedEvent event = MetricCollectedEvent.builder()
                .databaseConfigId(1L)
                .databaseName("TargetDB")
                .collectionStatus(CollectionStatus.CONNECTION_FAILED)
                .errorMessage("Connection refused to 192.168.1.100:3306")
                .timestamp(LocalDateTime.now())
                .build();

        List<IncidentCreatedEvent> incidents = engine.evaluateRisk(event);

        assertEquals(1, incidents.size());
        IncidentCreatedEvent incident = incidents.get(0);
        assertEquals(RiskSeverity.FATAL, incident.getSeverity());
        assertEquals("CONNECTION_FAILURE", incident.getRuleType());
        assertEquals(1L, incident.getDatabaseConfigId());
    }

    @Test
    @DisplayName("High active connections ratio triggers WARNING, CRITICAL, or FATAL incidents")
    void activeConnectionsRatio_triggersIncidents() {
        MetricCollectedEvent warningEvent = MetricCollectedEvent.builder()
                .databaseConfigId(1L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .activeConnections(82L)
                .maxConnections(100L)
                .build();

        List<IncidentCreatedEvent> warningIncidents = engine.evaluateRisk(warningEvent);
        assertEquals(1, warningIncidents.size());
        assertEquals(RiskSeverity.WARNING, warningIncidents.get(0).getSeverity());

        MetricCollectedEvent criticalEvent = MetricCollectedEvent.builder()
                .databaseConfigId(1L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .activeConnections(92L)
                .maxConnections(100L)
                .build();

        List<IncidentCreatedEvent> criticalIncidents = engine.evaluateRisk(criticalEvent);
        assertEquals(1, criticalIncidents.size());
        assertEquals(RiskSeverity.CRITICAL, criticalIncidents.get(0).getSeverity());

        MetricCollectedEvent fatalEvent = MetricCollectedEvent.builder()
                .databaseConfigId(1L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .activeConnections(97L)
                .maxConnections(100L)
                .build();

        List<IncidentCreatedEvent> fatalIncidents = engine.evaluateRisk(fatalEvent);
        assertEquals(1, fatalIncidents.size());
        assertEquals(RiskSeverity.FATAL, fatalIncidents.get(0).getSeverity());
    }

    @Test
    @DisplayName("CPU, Memory, and Slow Query thresholds trigger multiple incidents")
    void multipleThresholds_triggerMultipleIncidents() {
        MetricCollectedEvent event = MetricCollectedEvent.builder()
                .databaseConfigId(1L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .cpuUsage(92.5) // CRITICAL (>90)
                .memoryUsage(87.0) // WARNING (>85)
                .slowQueries(60L) // CRITICAL (>50)
                .build();

        List<IncidentCreatedEvent> incidents = engine.evaluateRisk(event);

        assertEquals(3, incidents.size());
        assertTrue(incidents.stream().anyMatch(i -> "CPU_USAGE_HIGH".equals(i.getRuleType()) && i.getSeverity() == RiskSeverity.CRITICAL));
        assertTrue(incidents.stream().anyMatch(i -> "MEMORY_USAGE_HIGH".equals(i.getRuleType()) && i.getSeverity() == RiskSeverity.WARNING));
        assertTrue(incidents.stream().anyMatch(i -> "SLOW_QUERIES_HIGH".equals(i.getRuleType()) && i.getSeverity() == RiskSeverity.CRITICAL));
    }

    @Test
    @DisplayName("Normal metrics trigger 0 incidents")
    void normalMetrics_triggerZeroIncidents() {
        MetricCollectedEvent event = MetricCollectedEvent.builder()
                .databaseConfigId(1L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .activeConnections(10L)
                .maxConnections(100L)
                .cpuUsage(30.0)
                .memoryUsage(50.0)
                .slowQueries(0L)
                .build();

        List<IncidentCreatedEvent> incidents = engine.evaluateRisk(event);

        assertTrue(incidents.isEmpty());
    }
}
