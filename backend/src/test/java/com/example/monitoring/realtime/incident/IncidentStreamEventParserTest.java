package com.example.monitoring.realtime.incident;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.risk.contract.SeverityTransition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentStreamEventParserTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IncidentStreamEventParser parser = new IncidentStreamEventParser(mapper);

    @Test
    void parsesUpdatedIncidentAndStripsInternalTransitionFromPublicData() {
        IncidentStreamEvent event = parser.parse(validUpdatedPayload());

        assertThat(event.eventId())
                .isEqualTo(UUID.fromString("30412297-1a93-44bc-9944-a8d8a03765aa"));
        assertThat(event.publishedAt()).isEqualTo(Instant.parse("2026-09-28T03:00:15.100Z"));
        assertThat(event.severityTransition()).isEqualTo(SeverityTransition.INCREASED);

        RealtimeIncidentMessage message = RealtimeIncidentMessage.from(event);
        JsonNode json = mapper.valueToTree(message);
        assertThat(message.eventType()).isEqualTo("IncidentUpdatedEvent");
        assertThat(message.databaseConfigId()).isEqualTo(12L);
        assertThat(json.path("data").has("severityTransition")).isFalse();
        assertThat(json.path("data").has("timestamp")).isFalse();
        assertThat(json.path("data").has("sourceEventId")).isFalse();
        assertThat(json.path("data").path("incidentVersion").asLong()).isEqualTo(2L);
    }

    @Test
    void acceptsDecreasedDirectionWithoutPublishingIt() {
        IncidentStreamEvent event = parser.parse(replace("INCREASED", "DECREASED"));

        assertThat(event.severityTransition()).isEqualTo(SeverityTransition.DECREASED);
        assertThat(mapper.valueToTree(RealtimeIncidentMessage.from(event)).toString())
                .doesNotContain("severityTransition", "DECREASED");
    }

    @Test
    void rejectsMissingUnknownOrMisplacedTransition() {
        assertReason(replace(",\n  \"severityTransition\":\"INCREASED\"", ""),
                "INVALID_INCIDENT_TRANSITION");
        assertReason(replace("INCREASED", "UNCHANGED"), "INVALID_INCIDENT_TRANSITION");
        assertReason(replace(
                "\"eventType\":\"IncidentUpdatedEvent\"",
                "\"eventType\":\"IncidentCreatedEvent\""),
                "INVALID_INCIDENT_TRANSITION");
    }

    @Test
    void rejectsEventTypeAndIncidentStatusMismatch() {
        assertReason(replace(
                "\"eventType\":\"IncidentUpdatedEvent\"",
                "\"eventType\":\"IncidentResolvedEvent\""),
                "INVALID_INCIDENT_EVENT");
    }

    static byte[] validUpdatedPayload() {
        return bytes("""
                {
                  "schemaVersion":1,
                  "eventId":"30412297-1a93-44bc-9944-a8d8a03765aa",
                  "eventType":"IncidentUpdatedEvent",
                  "publishedAt":"2026-09-28T03:00:15.100Z",
                  "timestamp":"2026-09-28T03:00:15.000Z",
                  "sourceEventId":"b43a63cb-5f28-4f4a-8de1-412c352ba79d",
                  "incidentId":"984b0ae3-37e5-46b1-a709-87bfb95b9a1a",
                  "databaseConfigId":12,
                  "databaseName":"Production MariaDB",
                  "ruleId":"CONNECTION_RATIO",
                  "ruleType":"CONNECTION_RATIO_EXCEEDED",
                  "severity":"CRITICAL",
                  "status":"OPEN",
                  "openedAt":"2026-09-28T03:00:00.000Z",
                  "lastObservedAt":"2026-09-28T03:00:15.000Z",
                  "resolvedAt":null,
                  "resolutionReason":null,
                  "metricName":"activeConnectionsRatio",
                  "metricValue":0.91,
                  "thresholdValue":0.90,
                  "sourceMetricId":501,
                  "message":"Connection ratio exceeded",
                  "incidentVersion":2,
                  "severityTransition":"INCREASED"
                }
                """);
    }

    private void assertReason(byte[] payload, String reason) {
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reason));
    }

    private byte[] replace(String target, String replacement) {
        return bytes(new String(validUpdatedPayload(), StandardCharsets.UTF_8).replace(target, replacement));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
