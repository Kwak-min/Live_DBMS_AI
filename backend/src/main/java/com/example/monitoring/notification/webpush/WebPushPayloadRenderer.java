package com.example.monitoring.notification.webpush;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class WebPushPayloadRenderer {
    private static final int MAX_PAYLOAD_BYTES = 3 * 1024;
    private static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;

    public WebPushPayloadRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] render(WebPushMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("deliveryId", message.deliveryId());
        payload.put("incidentId", message.incidentId().toString());
        payload.put("type", message.type().name());
        payload.put("title", message.title());
        payload.put("body", message.body());
        payload.put("url", "/incidents/" + message.incidentId());
        payload.put("tag", "incident:" + message.incidentId());
        payload.put("sentAt", UTC_MILLIS.format(message.sentAt()));
        try {
            byte[] result = objectMapper.writeValueAsBytes(payload);
            if (result.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Web Push payload is too large.");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to render Web Push payload.", exception);
        }
    }
}
