package com.example.monitoring.lifecycle.adapter;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.outbox.EventJson;
import com.example.monitoring.common.outbox.OutboxEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class LifecycleEventCodec {

    private final ObjectMapper objectMapper;
    private final Supplier<UUID> eventIds;

    LifecycleEventCodec(ObjectMapper objectMapper) {
        this(objectMapper, UUID::randomUUID);
    }

    LifecycleEventCodec(ObjectMapper objectMapper, Supplier<UUID> eventIds) {
        this.objectMapper = objectMapper;
        this.eventIds = eventIds;
    }

    String defaultPolicyJson() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("CONNECTION_RATIO", "activeConnectionsRatio",
                new BigDecimal("0.80"), new BigDecimal("0.90"), new BigDecimal("0.95")));
        rules.add(rule("SLOW_QUERY_RATE", "slowQueriesPerSecond",
                new BigDecimal("1.0"), new BigDecimal("5.0"), null));
        return writeJson(rules);
    }

    PreparedLifecycleEvent statusChanged(MonitoringStateWrite state) {
        UUID eventId = eventIds.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("databaseConfigId", state.databaseConfigId());
        payload.put("configVersion", state.configVersion());
        payload.put("deleted", state.deleted());
        payload.put("enabled", state.enabled());
        payload.put("connectionStatus", "UNKNOWN");
        payload.put("dataFreshness", state.dataFreshness());
        payload.put("riskLevel", null);
        payload.put("lastAttemptAt", null);
        payload.put("lastSuccessAt", null);
        payload.put("latestMetricId", null);
        payload.put("openIncidentIds", List.of());
        payload.put("stateVersion", state.stateVersion());
        payload.put("updatedAt", time(state.updatedAt()));
        return event(eventId, OutboxEventType.MONITORING_STATUS_CHANGED, state.databaseConfigId(), payload);
    }

    PreparedLifecycleEvent incidentResolved(IncidentResolution resolution) {
        UUID eventId = eventIds.get();
        LockedIncident incident = resolution.incident();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", time(resolution.resolvedAt()));
        payload.put("sourceEventId", null);
        payload.put("incidentId", incident.incidentId());
        payload.put("databaseConfigId", incident.databaseConfigId());
        payload.put("databaseName", incident.databaseName());
        payload.put("ruleId", incident.ruleId());
        payload.put("ruleType", incident.ruleType());
        payload.put("severity", incident.severity());
        payload.put("status", "RESOLVED");
        payload.put("openedAt", time(incident.openedAt()));
        payload.put("lastObservedAt", time(incident.lastObservedAt()));
        payload.put("resolvedAt", time(resolution.resolvedAt()));
        payload.put("resolutionReason", resolution.reason());
        payload.put("metricName", incident.metricName());
        payload.put("metricValue", incident.metricValue());
        payload.put("thresholdValue", incident.thresholdValue());
        payload.put("sourceMetricId", incident.sourceMetricId());
        payload.put("message", incident.message());
        payload.put("incidentVersion", resolution.nextIncidentVersion());
        return event(eventId, OutboxEventType.INCIDENT_RESOLVED, incident.databaseConfigId(), payload);
    }

    private PreparedLifecycleEvent event(
            UUID eventId,
            OutboxEventType eventType,
            long databaseConfigId,
            Map<String, Object> payload
    ) {
        EventJson.build(objectMapper, eventId, eventType.wireName(), Instant.EPOCH, payload);
        return new PreparedLifecycleEvent(
                eventId,
                eventType,
                databaseConfigId,
                Collections.unmodifiableMap(new LinkedHashMap<>(payload)));
    }

    private Map<String, Object> rule(
            String ruleId,
            String metricName,
            BigDecimal warning,
            BigDecimal critical,
            BigDecimal fatal
    ) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", ruleId);
        rule.put("metricName", metricName);
        rule.put("operator", "GTE");
        rule.put("warningThreshold", warning);
        rule.put("criticalThreshold", critical);
        rule.put("fatalThreshold", fatal);
        rule.put("sustainSeconds", 15);
        rule.put("recoverySeconds", 15);
        rule.put("enabled", true);
        return rule;
    }

    private String time(Instant instant) {
        return UtcInstantJacksonConfig.format(instant);
    }

    private String writeJson(Object value) {
        return new String(writeBytes(value), StandardCharsets.UTF_8);
    }

    private byte[] writeBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize monitoring lifecycle payload", exception);
        }
    }
}
