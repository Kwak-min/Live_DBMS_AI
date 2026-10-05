package com.example.monitoring.risk.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

public final class IncidentEventTransitionParser {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final ObjectMapper mapper;

    public IncidentEventTransitionParser(ObjectMapper objectMapper) {
        mapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public SeverityTransition parseUpdated(byte[] utf8Payload) {
        if (utf8Payload == null || utf8Payload.length > MAX_PAYLOAD_BYTES) {
            throw invalid(null);
        }
        JsonNode root;
        try {
            root = mapper.readTree(utf8Payload);
        } catch (IOException exception) {
            throw invalid(null);
        }
        UUID eventId = eventId(root);
        if (root == null || !root.isObject()
                || !root.path("schemaVersion").isIntegralNumber()
                || root.path("schemaVersion").intValue() != 1
                || eventId == null
                || !"IncidentUpdatedEvent".equals(root.path("eventType").textValue())) {
            throw invalid(eventId);
        }
        JsonNode transition = root.get("severityTransition");
        if (transition == null || !transition.isTextual()) {
            throw invalid(eventId);
        }
        try {
            return SeverityTransition.valueOf(transition.textValue());
        } catch (IllegalArgumentException exception) {
            throw invalid(eventId);
        }
    }

    private UUID eventId(JsonNode root) {
        if (root == null || !root.path("eventId").isTextual()) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(root.path("eventId").textValue());
            return parsed.toString().equals(root.path("eventId").textValue()) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private IncidentEventContractException invalid(UUID eventId) {
        return new IncidentEventContractException(
                "INVALID_INCIDENT_TRANSITION",
                "IncidentUpdatedEvent requires severityTransition INCREASED or DECREASED",
                eventId);
    }
}
