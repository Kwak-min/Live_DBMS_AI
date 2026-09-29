package com.example.monitoring.lifecycle.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class LifecycleEventCodec {

    static final int MAX_PAYLOAD_BYTES = 65_536;
    private static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder()
            .appendInstant(3)
            .toFormatter();

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

    SerializedLifecycleEvent statusChanged(MonitoringStateWrite state) {
        UUID eventId = eventIds.get();
        Map<String, Object> payload = common(eventId, "MonitoringStatusChangedEvent", state.updatedAt());
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
        return event(eventId, "MonitoringStatusChangedEvent", state.updatedAt(), payload);
    }

    SerializedLifecycleEvent incidentResolved(IncidentResolution resolution) {
        UUID eventId = eventIds.get();
        LockedIncident incident = resolution.incident();
        Map<String, Object> payload = common(eventId, "IncidentResolvedEvent", resolution.resolvedAt());
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
        return event(eventId, "IncidentResolvedEvent", resolution.resolvedAt(), payload);
    }

    private SerializedLifecycleEvent event(
            UUID eventId,
            String eventType,
            Instant publishedAt,
            Map<String, Object> payload
    ) {
        Instant normalizedPublishedAt = publishedAt.truncatedTo(ChronoUnit.MILLIS);
        byte[] bytes = writeBytes(payload);
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("Monitoring lifecycle payload exceeds 65536 bytes");
        }
        return new SerializedLifecycleEvent(
                eventId, eventType, normalizedPublishedAt, new String(bytes, StandardCharsets.UTF_8));
    }

    private Map<String, Object> common(UUID eventId, String eventType, Instant publishedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("eventId", eventId);
        payload.put("eventType", eventType);
        payload.put("publishedAt", time(publishedAt));
        return payload;
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
        return UTC_MILLIS.format(instant);
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
