package com.example.monitoring.realtime.status;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusStreamEventParserTest {

    private final StatusStreamEventParser parser = new StatusStreamEventParser(new ObjectMapper());

    @Test
    void parsesDeletedStatusAndBuildsTheExactPublicEnvelope() {
        StatusStreamEvent event = parser.parse(validPayload());

        assertThat(event.eventId())
                .isEqualTo(UUID.fromString("20412297-1a93-44bc-9944-a8d8a03765aa"));
        assertThat(event.publishedAt()).isEqualTo(Instant.parse("2026-09-28T03:00:00.100Z"));
        assertThat(event.status().deleted()).isTrue();
        assertThat(event.status().enabled()).isFalse();
        assertThat(event.status().openIncidentIds()).containsExactly(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"));

        RealtimeStatusMessage message = RealtimeStatusMessage.from(event);
        assertThat(message.schemaVersion()).isOne();
        assertThat(message.eventId()).isEqualTo(event.eventId());
        assertThat(message.eventType()).isEqualTo("MonitoringStatusChanged");
        assertThat(message.databaseConfigId()).isEqualTo(12L);
        assertThat(message.publishedAt()).isEqualTo(event.publishedAt());
        assertThat(message.data()).isEqualTo(event.status());
    }

    @Test
    void ignoresUnknownOptionalFieldsButRejectsUnknownTypeAndMissingRequiredFields() {
        assertThat(parser.parse(replace("\"stateVersion\":8", "\"stateVersion\":8,\"futureField\":true")))
                .isNotNull();
        assertReason(replace("MonitoringStatusChangedEvent", "UnknownEvent"), "UNSUPPORTED_EVENT_TYPE");
        assertReason(replace(",\n  \"updatedAt\":\"2026-09-28T03:00:00.080Z\"", ""),
                "MISSING_REQUIRED_FIELD");
    }

    @Test
    void rejectsUnsafeVersionsAndInvalidPausedCoherence() {
        assertReason(replace("\"stateVersion\":8", "\"stateVersion\":9007199254740992"),
                "INVALID_NUMERIC_VALUE");
        assertReason(replace("\"riskLevel\":null", "\"riskLevel\":\"INFO\""),
                "INVALID_STATUS_INVARIANT");
    }

    static byte[] validPayload() {
        return bytes("""
                {
                  "schemaVersion":1,
                  "eventId":"20412297-1a93-44bc-9944-a8d8a03765aa",
                  "eventType":"MonitoringStatusChangedEvent",
                  "publishedAt":"2026-09-28T03:00:00.100Z",
                  "databaseConfigId":12,
                  "configVersion":2,
                  "deleted":true,
                  "enabled":false,
                  "connectionStatus":"UNKNOWN",
                  "dataFreshness":"PAUSED",
                  "riskLevel":null,
                  "lastAttemptAt":null,
                  "lastSuccessAt":null,
                  "latestMetricId":null,
                  "openIncidentIds":[
                    "22222222-2222-4222-8222-222222222222",
                    "11111111-1111-4111-8111-111111111111"
                  ],
                  "stateVersion":8,
                  "updatedAt":"2026-09-28T03:00:00.080Z"
                }
                """);
    }

    private void assertReason(byte[] payload, String reason) {
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reason));
    }

    private byte[] replace(String target, String replacement) {
        return bytes(new String(validPayload(), StandardCharsets.UTF_8).replace(target, replacement));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
