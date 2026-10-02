package com.example.monitoring.risk.contract;

import com.example.monitoring.common.outbox.EventJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringContractCodecTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final UUID INCIDENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000222");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalRiskContractsAreAvailable() throws Exception {
        List<RiskRule> rules = MonitoringContracts.defaultRules();

        assertThat(rules).extracting(RiskRule::ruleId)
                .containsExactly(RuleId.CONNECTION_RATIO, RuleId.SLOW_QUERY_RATE);
        assertThat(objectMapper.valueToTree(rules).toString()).isEqualTo(
                "[{\"ruleId\":\"CONNECTION_RATIO\",\"metricName\":\"activeConnectionsRatio\","
                        + "\"operator\":\"GTE\",\"warningThreshold\":0.8,\"criticalThreshold\":0.9,"
                        + "\"fatalThreshold\":0.95,\"sustainSeconds\":15,\"recoverySeconds\":15,"
                        + "\"enabled\":true},{\"ruleId\":\"SLOW_QUERY_RATE\","
                        + "\"metricName\":\"slowQueriesPerSecond\",\"operator\":\"GTE\","
                        + "\"warningThreshold\":1,\"criticalThreshold\":5,\"fatalThreshold\":null,"
                        + "\"sustainSeconds\":15,\"recoverySeconds\":15,\"enabled\":true}]");
    }

    @Test
    void updatedEventCarriesDirectionWhilePublicIncidentAndOtherEventsDoNot() {
        Incident incident = openIncident();
        Instant transitionAt = Instant.parse("2026-10-02T03:04:05.123456789Z");

        JsonNode publicIncident = objectMapper.valueToTree(incident);
        IncidentEventPayload updated = IncidentEventPayload.updated(
                incident, transitionAt, EVENT_ID, SeverityTransition.INCREASED);
        JsonNode updatedJson = objectMapper.valueToTree(updated);
        JsonNode createdJson = objectMapper.valueToTree(
                IncidentEventPayload.created(incident, transitionAt, EVENT_ID));

        assertThat(updated.timestamp()).isEqualTo(Instant.parse("2026-10-02T03:04:05.123Z"));
        assertThat(publicIncident.has("severityTransition")).isFalse();
        assertThat(updatedJson.path("severityTransition").asText()).isEqualTo("INCREASED");
        assertThat(createdJson.has("severityTransition")).isFalse();
        assertThat(fieldNames(updatedJson)).containsExactlyInAnyOrder(
                "timestamp", "sourceEventId", "incidentId", "databaseConfigId", "databaseName",
                "ruleId", "ruleType", "severity", "status", "openedAt", "lastObservedAt",
                "resolvedAt", "resolutionReason", "metricName", "metricValue", "thresholdValue",
                "sourceMetricId", "message", "incidentVersion", "severityTransition");
    }

    @Test
    void acceptsBothRequiredUpdatedDirections() {
        IncidentEventTransitionParser parser = new IncidentEventTransitionParser(objectMapper);

        assertThat(parser.parseUpdated(updatedEnvelope("INCREASED")))
                .isEqualTo(SeverityTransition.INCREASED);
        assertThat(parser.parseUpdated(updatedEnvelope("DECREASED")))
                .isEqualTo(SeverityTransition.DECREASED);
    }

    @Test
    void rejectsUpdatedEventWithoutDirection() {
        IncidentEventTransitionParser parser = new IncidentEventTransitionParser(objectMapper);
        byte[] missing = ("{\"schemaVersion\":1,\"eventId\":\"" + EVENT_ID
                + "\",\"eventType\":\"IncidentUpdatedEvent\"}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parseUpdated(missing))
                .isInstanceOf(IncidentEventContractException.class)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_TRANSITION");
        assertThatThrownBy(() -> parser.parseUpdated(updatedEnvelope("SIDEWAYS")))
                .isInstanceOf(IncidentEventContractException.class)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_TRANSITION");
    }

    @Test
    void rejectsFractionalSchemaVersionAndDuplicateDirection() {
        IncidentEventTransitionParser parser = new IncidentEventTransitionParser(objectMapper);
        byte[] fractional = ("{\"schemaVersion\":1.5,\"eventId\":\"" + EVENT_ID
                + "\",\"eventType\":\"IncidentUpdatedEvent\","
                + "\"severityTransition\":\"INCREASED\"}").getBytes(StandardCharsets.UTF_8);
        byte[] duplicate = ("{\"schemaVersion\":1,\"eventId\":\"" + EVENT_ID
                + "\",\"eventType\":\"IncidentUpdatedEvent\","
                + "\"severityTransition\":\"INCREASED\","
                + "\"severityTransition\":\"DECREASED\"}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parseUpdated(fractional))
                .isInstanceOf(IncidentEventContractException.class)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_TRANSITION");
        assertThatThrownBy(() -> parser.parseUpdated(duplicate))
                .isInstanceOf(IncidentEventContractException.class)
                .extracting("code")
                .isEqualTo("INVALID_INCIDENT_TRANSITION");
    }

    @Test
    void canonicalRecordsEnforceSafeNumbersAndStableOrdering() {
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        StatusSnapshot status = new StatusSnapshot(
                12L,
                3L,
                false,
                true,
                ConnectionStatus.UP,
                DataFreshness.FRESH,
                RiskLevel.INFO,
                Instant.parse("2026-10-02T00:00:00.123456Z"),
                Instant.parse("2026-10-02T00:00:00.123456Z"),
                501L,
                List.of(high, low),
                7L,
                Instant.parse("2026-10-02T00:00:00.999999Z"));

        assertThat(status.openIncidentIds()).containsExactly(low, high);
        assertThat(status.updatedAt()).isEqualTo(Instant.parse("2026-10-02T00:00:00.999Z"));
        assertThatThrownBy(() -> new RiskPolicy(
                ContractChecks.MAX_SAFE_INTEGER + 1,
                1,
                30,
                300,
                MonitoringContracts.defaultRules(),
                Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JavaScript-safe");
    }

    private byte[] updatedEnvelope(String transition) {
        String json = "{\"schemaVersion\":1,\"eventId\":\"" + EVENT_ID
                + "\",\"eventType\":\"IncidentUpdatedEvent\",\"severityTransition\":\""
                + transition + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private Incident openIncident() {
        return new Incident(
                INCIDENT_ID,
                12L,
                "production",
                RuleId.CONNECTION_RATIO,
                RuleType.CONNECTION_RATIO_EXCEEDED,
                IncidentSeverity.CRITICAL,
                IncidentStatus.OPEN,
                Instant.parse("2026-10-02T03:00:00Z"),
                Instant.parse("2026-10-02T03:04:00Z"),
                null,
                null,
                "activeConnectionsRatio",
                new BigDecimal("0.91"),
                new BigDecimal("0.90"),
                501L,
                "Connection ratio is critical",
                2L);
    }

    private Set<String> fieldNames(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
