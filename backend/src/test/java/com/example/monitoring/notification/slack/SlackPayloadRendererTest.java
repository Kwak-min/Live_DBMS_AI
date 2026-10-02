package com.example.monitoring.notification.slack;

import com.example.monitoring.notification.transport.NotificationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackPayloadRendererTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SlackPayloadRenderer renderer = new SlackPayloadRenderer(mapper,
            new SlackIncidentLinkFactory("https://monitoring.example"));

    @Test
    void rendersExactEscapedPlainTextAndDisablesUnfurls() throws Exception {
        UUID incidentId = UUID.fromString("d38f135a-34c3-40df-915a-f26b2ebf4162");
        SlackPayload payload = renderer.render(new SlackMessage("CRITICAL", "DB <primary> & replica",
                "connections > 90%", NotificationType.INCIDENT_OPENED,
                Instant.parse("2026-10-03T01:02:03.456789Z"), incidentId));
        String expected = "[CRITICAL] DB &lt;primary&gt; &amp; replica · connections &gt; 90% · 발생 "
                + "2026-10-03T01:02:03.456Z · https://monitoring.example/incidents/" + incidentId;

        Map<String, Object> json = mapper.readValue(payload.body(), new TypeReference<>() { });
        assertThat(json).containsOnlyKeys("text", "blocks", "unfurl_links", "unfurl_media")
                .containsEntry("text", expected)
                .containsEntry("unfurl_links", false)
                .containsEntry("unfurl_media", false);
        Map<String, Object> section = (Map<String, Object>) ((java.util.List<?>) json.get("blocks")).get(0);
        Map<String, Object> text = (Map<String, Object>) section.get("text");
        assertThat(section).containsOnlyKeys("type", "text").containsEntry("type", "section");
        assertThat(text).containsOnlyKeys("type", "text")
                .containsEntry("type", "plain_text")
                .containsEntry("text", expected);
        assertThat(payload.text()).isEqualTo(expected);
    }

    @Test
    void resolvedNotificationUsesRecoveryAction() {
        SlackPayload payload = renderer.render(new SlackMessage("WARNING", "DB", "connections",
                NotificationType.INCIDENT_RESOLVED, Instant.parse("2026-10-03T01:02:03Z"), UUID.randomUUID()));
        assertThat(payload.text()).contains(" · 복구 2026-10-03T01:02:03.000Z · ");
    }

    @Test
    void allowsOnlyHttpsOrTheDocumentedLocalHttpOrigin() {
        UUID incidentId = UUID.randomUUID();
        assertThat(new SlackIncidentLinkFactory("http://localhost:5173").incident(incidentId))
                .isEqualTo("http://localhost:5173/incidents/" + incidentId);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SlackIncidentLinkFactory("http://monitoring.example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("monitoring.example");
    }
}
