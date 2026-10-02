package com.example.monitoring.realtime.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterPayloadSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeadLetterPayloadSanitizer sanitizer = new DeadLetterPayloadSanitizer(mapper);

    @Test
    void emitsOnlyAllowlistedTypedDiagnosticsFromValidObjectJson() throws Exception {
        String raw = """
                {
                  "schemaVersion":999,
                  "eventId":"b43a63cb-5f28-4f4a-8de1-412c352ba79d",
                  "eventType":"MetricCollectedEvent",
                  "publishedAt":"2026-09-28T03:00:00.050Z",
                  "metricId":501,
                  "databaseConfigId":12,
                  "configVersion":2,
                  "databaseName":"orders Bearer DATABASE_SECRET_42",
                  "timestamp":"2026-09-28T03:00:00.000Z",
                  "collectionAttemptTime":"2026-09-28T03:00:00.000Z",
                  "lastSuccessAt":null,
                  "cpuUsage":0,
                  "activeConnections":18,
                  "memoryUsage":101,
                  "qps":"Bearer QPS_SECRET_42",
                  "metricWindowSeconds":0,
                  "collectionStatus":"PARTIAL_FAILURE",
                  "errorCode":"QUERY_FAILED",
                  "errorMessage":"password=ERROR_SECRET_42",
                  "unavailableMetrics":{
                    "memoryUsage":"UNSUPPORTED",
                    "password":"WARMUP"
                  },
                  "password":"TOP_SECRET_42",
                  "note":"Bearer NOTE_SECRET_42",
                  "nested":{"label":"NESTED_SECRET_42"},
                  "items":["ARRAY_SECRET_42",{"authorization":"Bearer TOKEN_42"}]
                }
                """;

        JsonNode sanitized = mapper.readTree(sanitizer.sanitize(bytes(raw)));

        assertThat(sanitized).isEqualTo(mapper.readTree("""
                {
                  "schemaVersion":999,
                  "eventId":"b43a63cb-5f28-4f4a-8de1-412c352ba79d",
                  "eventType":"MetricCollectedEvent",
                  "publishedAt":"2026-09-28T03:00:00.050Z",
                  "metricId":501,
                  "databaseConfigId":12,
                  "configVersion":2,
                  "timestamp":"2026-09-28T03:00:00.000Z",
                  "collectionAttemptTime":"2026-09-28T03:00:00.000Z",
                  "lastSuccessAt":null,
                  "collectionStatus":"PARTIAL_FAILURE",
                  "errorCode":"QUERY_FAILED"
                }
                """));
        assertThat(sanitized.toString()).doesNotContain(
                "DATABASE_SECRET_42",
                "QPS_SECRET_42",
                "ERROR_SECRET_42",
                "TOP_SECRET_42",
                "NOTE_SECRET_42",
                "NESTED_SECRET_42",
                "ARRAY_SECRET_42",
                "TOKEN_42",
                "databaseName",
                "errorMessage",
                "note",
                "nested",
                "items",
                "cpuUsage",
                "activeConnections",
                "unavailableMetrics",
                "password");
    }

    @Test
    void replacesUnparseableAndNonObjectPayloadsWithConstantMarker() {
        assertThat(sanitizer.sanitize(bytes("password=TOP_SECRET_42")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("db.password: TOP_SECRET_42")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("{\"password\":\"TOP_SECRET_42")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(new byte[0]))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("\"TOP_SECRET_42\"")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("[\"TOP_SECRET_42\"]")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("null")))
                .isEqualTo("[UNPARSEABLE_JSON]");
    }

    @Test
    void marksInvalidUtf8WithoutRetainingBytes() {
        assertThat(sanitizer.sanitize(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isEqualTo("[INVALID_UTF8]");
    }

    @Test
    void boundsSanitizedPayloadTo64KiBUtf8() {
        String raw = "{\"note\":\"" + "가".repeat(30_000) + "\"}";

        byte[] sanitized = sanitizer.sanitize(bytes(raw)).getBytes(StandardCharsets.UTF_8);

        assertThat(sanitized.length).isLessThanOrEqualTo(64 * 1024);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
