package com.example.monitoring.realtime.event;

import com.example.monitoring.realtime.redis.MetricPayloadException;
import com.example.monitoring.realtime.redis.MetricPayloadParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MetricPayloadParserTest {

    private final MetricPayloadParser parser = new MetricPayloadParser(new ObjectMapper());

    @Test
    void preservesNullAndMeasuredZeroWhileMappingTheFullApiMetric() {
        MetricCollectedPayloadV1 payload = parser.parse(validPayload().getBytes(StandardCharsets.UTF_8));

        RealtimeMetricMessage message = RealtimeMetricMessage.from(payload);

        assertThat(message.schemaVersion()).isEqualTo(1);
        assertThat(message.eventId().toString()).isEqualTo("7f6a1c08-9ef4-45bd-bc2e-5b2d1c1a2f11");
        assertThat(message.eventType()).isEqualTo("MetricUpdated");
        assertThat(message.publishedAt().toString()).isEqualTo("2026-09-28T03:00:20.050Z");
        assertThat(message.data().id()).isEqualTo(503);
        assertThat(message.data().cpuUsage()).isNull();
        assertThat(message.data().activeConnections()).isZero();
        assertThat(message.data().qps()).isZero();
        assertThat(message.data().slowQueries()).isZero();
        assertThat(message.data().storageBytes()).isZero();
        assertThat(message.data().unavailableMetrics())
                .containsEntry("cpuUsage", MetricCollectedPayloadV1.UnavailableReason.UNSUPPORTED)
                .doesNotContainKey("activeConnections");
    }

    @Test
    void serializesEveryEnvelopeTimeWithFixedUtcMilliseconds() throws Exception {
        MetricCollectedPayloadV1 payload = parser.parse(validPayload().getBytes(StandardCharsets.UTF_8));
        ObjectMapper outputMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        JsonNode json = outputMapper.readTree(outputMapper.writeValueAsBytes(RealtimeMetricMessage.from(payload)));

        assertThat(json.get("publishedAt").textValue()).isEqualTo("2026-09-28T03:00:20.050Z");
        assertThat(json.at("/data/timestamp").textValue()).isEqualTo("2026-09-28T03:00:20.000Z");
        assertThat(json.at("/data/collectionAttemptTime").textValue())
                .isEqualTo("2026-09-28T03:00:20.000Z");
        assertThat(json.at("/data/lastSuccessAt").textValue()).isEqualTo("2026-09-28T03:00:20.000Z");
    }

    @Test
    void ignoresUnknownOptionalFields() {
        String json = validPayload().replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"futureHint\":true,");

        assertThat(parser.parse(json.getBytes(StandardCharsets.UTF_8)).metricId()).isEqualTo(503);
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        String json = validPayload().replace("\"metricId\":503,", "\"metricId\":503,\"metricId\":504,");

        assertReason(json, "DUPLICATE_JSON_KEY");
    }

    @Test
    void rejectsMissingNullableFields() {
        String json = validPayload().replace("\"cpuUsage\":null,", "");

        assertReason(json, "MISSING_REQUIRED_FIELD");
    }

    @Test
    void rejectsUnknownEnumsAndUnsafeOrNonFiniteNumbers() {
        assertReason(validPayload().replace("\"collectionStatus\":\"SUCCESS\"", "\"collectionStatus\":\"OK\""),
                "INVALID_ENUM_VALUE");
        assertReason(validPayload().replace("\"metricId\":503", "\"metricId\":9007199254740992"),
                "INVALID_NUMERIC_VALUE");
        assertReason(validPayload().replace("\"qps\":0", "\"qps\":1e400"),
                "INVALID_NUMERIC_VALUE");
    }

    @Test
    void rejectsUnknownVersionAndEventType() {
        assertReason(validPayload().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                "UNSUPPORTED_SCHEMA_VERSION");
        assertReason(validPayload().replace("\"eventType\":\"MetricCollectedEvent\"", "\"eventType\":\"Other\""),
                "UNSUPPORTED_EVENT_TYPE");
    }

    @Test
    void rejectsInvalidUtf8OversizeAndTrailingJson() {
        assertReason(new byte[]{(byte) 0xC3, (byte) 0x28}, "INVALID_UTF8");
        assertReason(new byte[64 * 1024 + 1], "PAYLOAD_TOO_LARGE");
        assertReason((validPayload() + " {}").getBytes(StandardCharsets.UTF_8), "INVALID_JSON");
    }

    private void assertReason(String json, String reasonCode) {
        assertReason(json.getBytes(StandardCharsets.UTF_8), reasonCode);
    }

    private void assertReason(byte[] payload, String reasonCode) {
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOf(MetricPayloadException.class)
                .extracting(error -> ((MetricPayloadException) error).reasonCode())
                .isEqualTo(reasonCode);
    }

    public static String validPayload() {
        return """
                {
                  "schemaVersion":1,
                  "eventId":"7f6a1c08-9ef4-45bd-bc2e-5b2d1c1a2f11",
                  "eventType":"MetricCollectedEvent",
                  "publishedAt":"2026-09-28T03:00:20.050Z",
                  "metricId":503,
                  "databaseConfigId":12,
                  "configVersion":2,
                  "databaseName":"운영 MariaDB",
                  "timestamp":"2026-09-28T03:00:20.000Z",
                  "collectionAttemptTime":"2026-09-28T03:00:20.000Z",
                  "lastSuccessAt":"2026-09-28T03:00:20.000Z",
                  "cpuUsage":null,
                  "memoryUsage":null,
                  "activeConnections":0,
                  "maxConnections":151,
                  "qps":0,
                  "slowQueries":0,
                  "slowQueriesDelta":0,
                  "slowQueriesPerSecond":0,
                  "metricWindowSeconds":5,
                  "threadsRunning":0,
                  "storageBytes":0,
                  "responseTimeMs":9,
                  "collectionStatus":"SUCCESS",
                  "errorCode":null,
                  "errorMessage":null,
                  "unavailableMetrics":{"cpuUsage":"UNSUPPORTED","memoryUsage":"UNSUPPORTED"}
                }
                """;
    }
}
