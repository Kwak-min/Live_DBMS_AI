package com.example.monitoring.common.outbox;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 내부 이벤트 JSON 생성 (docs/events.md 2절). 공통 필드를 앞에 두고 payload 필드를 이어 붙인다.
 * outbox 이벤트와 outbox를 쓰지 않는 CollectorHeartbeatEvent가 같은 형식을 쓴다.
 */
public final class EventJson {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final Set<String> RESERVED_FIELDS = Set.of("schemaVersion", "eventId", "eventType", "publishedAt");

    private EventJson() {
    }

    /**
     * @throws IllegalArgumentException payload가 JSON 객체가 아니거나, 공통 필드를 포함하거나, 64KiB를 넘을 때
     */
    public static String build(ObjectMapper objectMapper, UUID eventId, String eventType, Instant publishedAt,
                               Object payload) {
        JsonNode body = objectMapper.valueToTree(payload);
        if (!body.isObject()) {
            throw new IllegalArgumentException("Event payload must serialize to a JSON object: " + eventType);
        }
        body.fieldNames().forEachRemaining(field -> {
            if (RESERVED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Event payload must not contain common field '" + field + "'");
            }
        });

        ObjectNode event = objectMapper.createObjectNode();
        event.put("schemaVersion", SCHEMA_VERSION);
        event.put("eventId", eventId.toString());
        event.put("eventType", eventType);
        event.put("publishedAt", UtcInstantJacksonConfig.format(publishedAt));
        event.setAll((ObjectNode) body);

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize event payload: " + eventType, e);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Event payload exceeds 64KiB: " + eventType);
        }
        return json;
    }
}
