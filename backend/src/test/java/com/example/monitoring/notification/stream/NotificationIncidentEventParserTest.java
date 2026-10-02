package com.example.monitoring.notification.stream;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.risk.contract.IncidentStatus;
import com.example.monitoring.risk.contract.SeverityTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationIncidentEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("b43a63cb-5f28-4f4a-8de1-412c352ba79d");
    private final NotificationIncidentEventParser parser =
            new NotificationIncidentEventParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesDurableIncreasedDirectionAndIgnoresUnknownOptionalFields() {
        NotificationIncidentEvent event = parser.parse(replace(event("IncidentUpdatedEvent", "OPEN",
                "INCREASED", null, null), "}", ",\"futureField\":true}"));

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventType()).isEqualTo(NotificationIncidentEvent.Type.UPDATED);
        assertThat(event.publishedAt()).isEqualTo(Instant.parse("2026-09-28T03:00:15.050Z"));
        assertThat(event.incident().status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(event.incident().severityTransition()).isEqualTo(SeverityTransition.INCREASED);
    }

    @Test
    void missingOrUnknownUpdateDirectionFailsClosedImmediately() {
        assertInvalidTransition(event("IncidentUpdatedEvent", "OPEN", null, null, null));
        assertInvalidTransition(event("IncidentUpdatedEvent", "OPEN", "SIDEWAYS", null, null));
    }

    @Test
    void createdAndResolvedEventsRejectTransitionFieldOrIncoherentStatus() {
        assertThatThrownBy(() -> parser.parse(event("IncidentCreatedEvent", "OPEN",
                "INCREASED", null, null)))
                .isInstanceOf(InvalidStreamRecordException.class);
        assertThatCode(() -> parser.parse(event("IncidentResolvedEvent", "RESOLVED",
                null, "2026-09-28T03:00:15.000Z", "RECOVERED")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> parser.parse(event("IncidentResolvedEvent", "OPEN",
                null, null, null)))
                .isInstanceOf(InvalidStreamRecordException.class);
    }

    @Test
    void rejectsDuplicateKeysNonCanonicalTimeAndUnsafeIds() {
        assertThatThrownBy(() -> parser.parse(replace(event("IncidentCreatedEvent", "OPEN", null, null, null),
                "\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("DUPLICATE_JSON_KEY"));
        assertThatThrownBy(() -> parser.parse(replace(event("IncidentCreatedEvent", "OPEN", null, null, null),
                "2026-09-28T03:00:15.050Z", "2026-09-28T03:00:15Z")))
                .isInstanceOf(InvalidStreamRecordException.class);
        assertThatThrownBy(() -> parser.parse(replace(event("IncidentCreatedEvent", "OPEN", null, null, null),
                "\"databaseConfigId\":12", "\"databaseConfigId\":9007199254740992")))
                .isInstanceOf(InvalidStreamRecordException.class);
    }

    private void assertInvalidTransition(byte[] payload) {
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class, failure -> {
                    assertThat(failure.reasonCode()).isEqualTo("INVALID_INCIDENT_TRANSITION");
                    assertThat(failure.eventId()).isEqualTo(EVENT_ID);
                });
    }

    private byte[] event(String eventType, String status, String transition,
                         String resolvedAt, String resolutionReason) {
        String direction = transition == null ? "" : ",\"severityTransition\":\"" + transition + "\"";
        String json = """
                {"schemaVersion":1,"eventId":"%s","eventType":"%s",
                 "publishedAt":"2026-09-28T03:00:15.050Z","timestamp":"2026-09-28T03:00:15.000Z",
                 "sourceEventId":null,"incidentId":"984b0ae3-37e9-46b1-a709-87bfb95b9a1a",
                 "databaseConfigId":12,"databaseName":"operations","ruleId":"CONNECTION_RATIO",
                 "ruleType":"CONNECTION_RATIO_EXCEEDED","severity":"CRITICAL","status":"%s",
                 "openedAt":"2026-09-28T03:00:00.000Z","lastObservedAt":"2026-09-28T03:00:15.000Z",
                 "resolvedAt":%s,"resolutionReason":%s,"metricName":"activeConnectionsRatio",
                 "metricValue":0.91,"thresholdValue":0.90,"sourceMetricId":501,
                 "message":"threshold exceeded","incidentVersion":2%s}
                """.formatted(EVENT_ID, eventType, status, nullable(resolvedAt), nullable(resolutionReason), direction);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String nullable(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private byte[] replace(byte[] payload, String target, String replacement) {
        return new String(payload, StandardCharsets.UTF_8)
                .replace(target, replacement)
                .getBytes(StandardCharsets.UTF_8);
    }
}
