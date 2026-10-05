package com.example.monitoring.contract;

import com.example.monitoring.notification.api.PushSubscriptionRequest;
import com.example.monitoring.notification.stream.NotificationIncidentEventParser;
import com.example.monitoring.realtime.incident.IncidentStreamEventParser;
import com.example.monitoring.realtime.incident.RealtimeIncidentMessage;
import com.example.monitoring.realtime.redis.MetricPayloadParser;
import com.example.monitoring.realtime.status.RealtimeStatusMessage;
import com.example.monitoring.realtime.status.StatusStreamEventParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PartCDocumentationContractTest {

    private static final Path CONTRACT = Path.of("..", "docs", "contract-examples", "part-c.json");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyFixtureUsesItsNamedCanonicalCodec() throws Exception {
        JsonNode document = readDocument();
        JsonNode fixtures = document.required("fixtures");
        int checked = 0;
        for (JsonNode check : document.required("codecChecks")) {
            JsonNode fixture = fixtures.required(check.required("fixture").textValue());
            parse(check.required("codec").textValue(), fixture);
            checked++;
        }
        assertThat(checked).isEqualTo(document.required("codecChecks").size());
        assertThat(checked).isGreaterThan(0);
    }

    @Test
    void publicExamplesContainNoInternalFieldsAndUseUrl() throws Exception {
        JsonNode document = readDocument();
        JsonNode fixtures = document.required("fixtures");
        Set<String> internal = new HashSet<>();
        for (JsonNode field : document.required("internalOnlyFields")) {
            internal.add(field.textValue());
        }
        for (JsonNode name : document.required("publicFixtures")) {
            String fixtureName = name.textValue();
            assertNoInternalFields(fixtureName, fixtures.required(fixtureName), internal);
        }
        JsonNode push = fixtures.required("webPushPayload");
        assertThat(push.required("url").textValue())
                .isEqualTo("/incidents/984b0ae3-37e9-46b1-a709-87bfb95b9a1a");
        assertThat(push.has("path")).isFalse();
    }

    @Test
    void internalTransitionAndWindowRemainExact() throws Exception {
        JsonNode fixtures = readDocument().required("fixtures");
        assertThat(fixtures.required("incidentUpdatedIncreasedEvent")
                .required("severityTransition").textValue()).isEqualTo("INCREASED");
        assertThat(fixtures.required("incidentUpdatedDecreasedEvent")
                .required("severityTransition").textValue()).isEqualTo("DECREASED");
        assertThat(fixtures.required("incidentCreatedEvent").has("severityTransition")).isFalse();
        assertThat(fixtures.required("incidentResolvedEvent").has("severityTransition")).isFalse();
        JsonNode schedule = fixtures.required("deliverySchedule");
        assertThat(Duration.between(Instant.parse(schedule.required("eligibleAt").textValue()),
                Instant.parse(schedule.required("expiresAt").textValue())))
                .isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void expirationTimeIsARequiredNullableRequestMember() throws Exception {
        JsonNode fixture = readDocument().required("fixtures").required("pushSubscriptionInput");
        assertThat(fixture.has("expirationTime")).isTrue();
        assertThat(fixture.get("expirationTime").isNull()).isTrue();
        PushSubscriptionRequest request = mapper.treeToValue(fixture, PushSubscriptionRequest.class);
        assertThat(request.expirationTime()).isNull();
    }

    @Test
    void migrationBoundaryKeepsV1ToV4ImmutableAndOnlyPrivateV5Receipt() throws Exception {
        JsonNode boundary = readDocument().required("migrationBoundary");
        JsonNode immutable = boundary.required("immutableMigrations");
        assertThat(immutable).hasSize(4);
        assertThat(immutable.get(0).textValue()).isEqualTo("V1");
        assertThat(immutable.get(1).textValue()).isEqualTo("V2");
        assertThat(immutable.get(2).textValue()).isEqualTo("V3");
        assertThat(immutable.get(3).textValue()).isEqualTo("V4");

        JsonNode delta = boundary.required("authorizedSchemaDelta");
        assertThat(delta.required("migration").textValue()).isEqualTo("V5");
        assertThat(delta.required("table").textValue())
                .isEqualTo("notification_success_receipts");
        assertThat(delta.required("private").booleanValue()).isTrue();
        assertThat(delta.required("compact").booleanValue()).isTrue();
        assertThat(boundary.required("additionalEligibilityColumn").booleanValue()).isFalse();
        assertThat(boundary.required("publicFixtureExposure").booleanValue()).isFalse();
    }

    private JsonNode readDocument() throws Exception {
        return mapper.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
    }

    private void parse(String codec, JsonNode fixture) throws Exception {
        byte[] json = mapper.writeValueAsBytes(fixture);
        switch (codec) {
            case "MetricPayloadParser" -> new MetricPayloadParser(mapper).parse(json);
            case "StatusStreamEventParser" -> new StatusStreamEventParser(mapper).parse(json);
            case "IncidentStreamEventParser" -> new IncidentStreamEventParser(mapper).parse(json);
            case "NotificationIncidentEventParser" -> new NotificationIncidentEventParser(mapper).parse(json);
            case "RealtimeIncidentMessage" -> mapper.treeToValue(fixture, RealtimeIncidentMessage.class);
            case "RealtimeStatusMessage" -> mapper.treeToValue(fixture, RealtimeStatusMessage.class);
            case "PushSubscriptionRequest" -> mapper.treeToValue(fixture, PushSubscriptionRequest.class);
            case "WebPushPayload", "DeliveryResponse", "DeliverySchedule", "ErrorResponse" ->
                    assertThat(fixture.isObject()).as("%s must be an object", codec).isTrue();
            default -> throw new AssertionError("No canonical codec mapping for " + codec);
        }
    }

    private void assertNoInternalFields(String fixtureName, JsonNode node, Set<String> internal) {
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                assertThat(internal).as("public fixture %s contains %s", fixtureName, field)
                        .doesNotContain(field);
                assertNoInternalFields(fixtureName, node.get(field), internal);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoInternalFields(fixtureName, child, internal);
            }
        }
    }
}
