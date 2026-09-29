package com.example.monitoring.lifecycle.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleEventCodecTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final UUID INCIDENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000222");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LifecycleEventCodec codec = new LifecycleEventCodec(objectMapper, () -> EVENT_ID);

    @Test
    void defaultPolicyContainsExactlyTheTwoEnabledDocumentedRules() throws Exception {
        JsonNode actual = objectMapper.readTree(codec.defaultPolicyJson());
        JsonNode expected = objectMapper.readTree("""
                [
                  {
                    "ruleId":"CONNECTION_RATIO",
                    "metricName":"activeConnectionsRatio",
                    "operator":"GTE",
                    "warningThreshold":0.80,
                    "criticalThreshold":0.90,
                    "fatalThreshold":0.95,
                    "sustainSeconds":15,
                    "recoverySeconds":15,
                    "enabled":true
                  },
                  {
                    "ruleId":"SLOW_QUERY_RATE",
                    "metricName":"slowQueriesPerSecond",
                    "operator":"GTE",
                    "warningThreshold":1.0,
                    "criticalThreshold":5.0,
                    "fatalThreshold":null,
                    "sustainSeconds":15,
                    "recoverySeconds":15,
                    "enabled":true
                  }
                ]
                """);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void statusPayloadIsFlatCompleteMillisecondUtcAndHasNoAuditContextFields() throws Exception {
        MonitoringStateWrite state = new MonitoringStateWrite(
                12L,
                3L,
                7L,
                false,
                true,
                "PAUSED",
                null,
                Instant.parse("2026-09-29T03:04:05.123456789Z"));

        SerializedLifecycleEvent event = codec.statusChanged(state);
        JsonNode payload = objectMapper.readTree(event.json());

        assertThat(event.eventType()).isEqualTo("MonitoringStatusChangedEvent");
        assertThat(event.publishedAt()).isEqualTo(Instant.parse("2026-09-29T03:04:05.123Z"));
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
                "schemaVersion", "eventId", "eventType", "publishedAt",
                "databaseConfigId", "configVersion", "deleted", "enabled",
                "connectionStatus", "dataFreshness", "riskLevel", "lastAttemptAt",
                "lastSuccessAt", "latestMetricId", "openIncidentIds", "stateVersion", "updatedAt");
        assertThat(payload.path("schemaVersion").asInt()).isOne();
        assertThat(payload.path("eventId").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(payload.path("publishedAt").asText()).isEqualTo("2026-09-29T03:04:05.123Z");
        assertThat(payload.path("updatedAt").asText()).isEqualTo("2026-09-29T03:04:05.123Z");
        assertThat(payload.path("deleted").asBoolean()).isTrue();
        assertThat(payload.path("enabled").asBoolean()).isFalse();
        assertThat(payload.path("dataFreshness").asText()).isEqualTo("PAUSED");
        assertThat(payload.path("connectionStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(payload.path("riskLevel").isNull()).isTrue();
        assertThat(payload.path("lastAttemptAt").isNull()).isTrue();
        assertThat(payload.path("lastSuccessAt").isNull()).isTrue();
        assertThat(payload.path("latestMetricId").isNull()).isTrue();
        assertThat(payload.path("openIncidentIds").isArray()).isTrue();
        assertThat(payload.path("openIncidentIds").isEmpty()).isTrue();
        assertThat(payload.has("actorId")).isFalse();
        assertThat(payload.has("requestId")).isFalse();
    }

    @Test
    void resolvedIncidentPayloadRetainsEvidenceAndSetsAdministrativeFields() throws Exception {
        LockedIncident incident = incident("증거 메시지", 9L);
        Instant resolvedAt = Instant.parse("2026-09-29T04:05:06.987654321Z");

        SerializedLifecycleEvent event = codec.incidentResolved(
                new IncidentResolution(incident, 10L, "CONFIG_CHANGED", resolvedAt));
        JsonNode payload = objectMapper.readTree(event.json());

        assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
                "schemaVersion", "eventId", "eventType", "publishedAt", "timestamp", "sourceEventId",
                "incidentId", "databaseConfigId", "databaseName", "ruleId", "ruleType", "severity",
                "status", "openedAt", "lastObservedAt", "resolvedAt", "resolutionReason", "metricName",
                "metricValue", "thresholdValue", "sourceMetricId", "message", "incidentVersion");
        assertThat(payload.path("eventType").asText()).isEqualTo("IncidentResolvedEvent");
        assertThat(payload.path("publishedAt").asText()).isEqualTo("2026-09-29T04:05:06.987Z");
        assertThat(payload.path("timestamp").asText()).isEqualTo("2026-09-29T04:05:06.987Z");
        assertThat(payload.path("resolvedAt").asText()).isEqualTo("2026-09-29T04:05:06.987Z");
        assertThat(payload.path("sourceEventId").isNull()).isTrue();
        assertThat(payload.path("incidentId").asText()).isEqualTo(INCIDENT_ID.toString());
        assertThat(payload.path("databaseName").asText()).isEqualTo("original name");
        assertThat(payload.path("metricValue").decimalValue()).isEqualByComparingTo("0.91");
        assertThat(payload.path("thresholdValue").decimalValue()).isEqualByComparingTo("0.90");
        assertThat(payload.path("sourceMetricId").asLong()).isEqualTo(501L);
        assertThat(payload.path("message").asText()).isEqualTo("증거 메시지");
        assertThat(payload.path("incidentVersion").asLong()).isEqualTo(10L);
        assertThat(payload.path("resolutionReason").asText()).isEqualTo("CONFIG_CHANGED");
        assertThat(payload.has("actorId")).isFalse();
        assertThat(payload.has("requestId")).isFalse();
    }

    @Test
    void payloadLimitUsesSerializedUtf8BytesAndAllowsExactly65536() {
        LockedIncident emptyMessage = incident("", 1L);
        IncidentResolution baselineResolution = new IncidentResolution(
                emptyMessage, 2L, "CONFIG_CHANGED", Instant.parse("2026-09-29T04:05:06Z"));
        int baselineBytes = bytes(codec.incidentResolved(baselineResolution).json());
        String exactMessage = "a".repeat(LifecycleEventCodec.MAX_PAYLOAD_BYTES - baselineBytes);

        SerializedLifecycleEvent exact = codec.incidentResolved(new IncidentResolution(
                incident(exactMessage, 1L), 2L, "CONFIG_CHANGED", baselineResolution.resolvedAt()));

        assertThat(bytes(exact.json())).isEqualTo(LifecycleEventCodec.MAX_PAYLOAD_BYTES);
        assertThatThrownBy(() -> codec.incidentResolved(new IncidentResolution(
                incident(exactMessage + "가", 1L), 2L, "CONFIG_CHANGED", baselineResolution.resolvedAt())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Monitoring lifecycle payload exceeds 65536 bytes");
    }

    private LockedIncident incident(String message, long incidentVersion) {
        return new LockedIncident(
                INCIDENT_ID,
                12L,
                "original name",
                "CONNECTION_RATIO",
                "CONNECTION_RATIO_EXCEEDED",
                "CRITICAL",
                Instant.parse("2026-09-29T04:00:00Z"),
                Instant.parse("2026-09-29T04:04:00Z"),
                "activeConnectionsRatio",
                new BigDecimal("0.91"),
                new BigDecimal("0.90"),
                501L,
                message,
                incidentVersion);
    }

    private Set<String> fieldNames(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private int bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }
}
