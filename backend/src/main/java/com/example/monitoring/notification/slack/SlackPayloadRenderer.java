package com.example.monitoring.notification.slack;

import com.example.monitoring.notification.transport.NotificationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class SlackPayloadRenderer {
    private static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final SlackIncidentLinkFactory incidentLinks;

    public SlackPayloadRenderer(ObjectMapper objectMapper, SlackIncidentLinkFactory incidentLinks) {
        this.objectMapper = objectMapper;
        this.incidentLinks = incidentLinks;
    }

    public SlackPayload render(SlackMessage message) {
        String action = message.type() == NotificationType.INCIDENT_RESOLVED ? "복구" : "발생";
        String text = '[' + escape(message.severity()) + "] " + escape(message.displayName())
                + " · " + escape(message.ruleName()) + " · " + action + ' '
                + UTC_MILLIS.format(message.occurredAt()) + " · " + incidentLinks.incident(message.incidentId());

        Map<String, Object> plainText = new LinkedHashMap<>();
        plainText.put("type", "plain_text");
        plainText.put("text", text);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "section");
        section.put("text", plainText);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("blocks", List.of(section));
        payload.put("unfurl_links", false);
        payload.put("unfurl_media", false);
        try {
            return new SlackPayload(text, objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to render Slack notification.", exception);
        }
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
