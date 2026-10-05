package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorHeartbeatEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString(
            "9bfab7ee-221a-4fb4-9507-6fd6f4df7e83");
    private final CollectorHeartbeatEventParser parser =
            new CollectorHeartbeatEventParser(new ObjectMapper());

    @Test
    void parsesCanonicalHeartbeatAndIgnoresUnknownOptionalField() {
        CollectorHeartbeatEvent event = parser.parse(bytes("""
                {"schemaVersion":1,
                 "eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "eventType":"CollectorHeartbeatEvent",
                 "publishedAt":"2026-10-02T00:00:30.000Z",
                 "collectorId":"collector-a",
                 "timestamp":"2026-10-02T00:00:30.000Z",
                 "lastCycleStartedAt":"2026-10-02T00:00:25.000Z",
                 "lastCycleCompletedAt":null,
                 "cycleInProgress":true,
                 "futureDiagnostic":{"supportedLater":true}}
                """));

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.collectorId()).isEqualTo("collector-a");
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-10-02T00:00:30Z"));
        assertThat(event.lastCycleStartedAt())
                .isEqualTo(Instant.parse("2026-10-02T00:00:25Z"));
        assertThat(event.lastCycleCompletedAt()).isNull();
        assertThat(event.cycleInProgress()).isTrue();
    }

    @Test
    void rejectsFractionalVersionDuplicateKeyAndMissingNullableField() {
        assertReason(canonical().replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
                "INVALID_FIELD_TYPE");
        assertReason(canonical().replace(
                        "\"collectorId\":\"collector-a\"",
                        "\"collectorId\":\"collector-a\",\"collectorId\":\"collector-b\""),
                "DUPLICATE_JSON_KEY");
        assertReason(canonical().replace("\"lastCycleCompletedAt\":null,", ""),
                "MISSING_REQUIRED_FIELD");
    }

    private String canonical() {
        return """
                {"schemaVersion":1,
                 "eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "eventType":"CollectorHeartbeatEvent",
                 "publishedAt":"2026-10-02T00:00:30.000Z",
                 "collectorId":"collector-a",
                 "timestamp":"2026-10-02T00:00:30.000Z",
                 "lastCycleStartedAt":null,
                 "lastCycleCompletedAt":null,
                 "cycleInProgress":false}
                """;
    }

    private void assertReason(String json, String reason) {
        assertThatThrownBy(() -> parser.parse(bytes(json)))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reason));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
