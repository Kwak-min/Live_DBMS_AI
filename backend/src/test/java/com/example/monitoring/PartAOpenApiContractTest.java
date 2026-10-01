package com.example.monitoring;

import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 담당 REST(메트릭 3종·Ping)의 생성 OpenAPI가 docs/api.md 1·3·4절과 contract-examples.json을 따르는지 검증한다
 * (integration-handoff.md 6절 2항).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.collector.enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.database-security.verify-on-startup=false"
})
@ActiveProfiles("local")
class PartAOpenApiContractTest {

    private static final Path CONTRACT_EXAMPLES = Path.of("..", "docs", "contract-examples.json");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ERROR_REF = "#/components/schemas/ApiErrorResponse";
    private static final Map<String, String> OPERATIONS = Map.of(
            "/api/v1/metrics/{dbId}/latest", "get",
            "/api/v1/metrics/{dbId}/recent", "get",
            "/api/v1/metrics/{dbId}/history", "get",
            "/api/v1/databases/{id}/ping", "post");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var postgres = EmbeddedPostgresSupport.postgres();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.auth.jwt-signing-keys", () -> "{\"test\":\"" + KEY + "\"}");
        registry.add("app.auth.jwt-active-kid", () -> "test");
        registry.add("app.database-security.encryption-keys",
                () -> EmbeddedPostgresSupport.MIGRATION_PROPERTIES.get("DB_CONFIG_ENCRYPTION_KEYS"));
        registry.add("app.database-security.active-key-version", () -> "1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode api;

    @Test
    @DisplayName("Error responses use ApiErrorResponse JSON, 204 has no body and success bodies are application/json")
    void responsesFollowCommonHttpContract() throws Exception {
        JsonNode paths = api().path("paths");
        for (var operation : OPERATIONS.entrySet()) {
            JsonNode responses = paths.path(operation.getKey()).path(operation.getValue()).path("responses");
            assertThat(responses.isObject()).as(operation.getKey()).isTrue();
            assertThat(responses.has("401")).as("%s 401", operation.getKey()).isTrue();
            assertThat(responses.has("429")).as("%s 429", operation.getKey()).isTrue();
            assertThat(responses.path("429").path("headers").has("Retry-After"))
                    .as("%s Retry-After", operation.getKey()).isTrue();
            responses.fields().forEachRemaining(entry -> {
                String code = entry.getKey();
                JsonNode content = entry.getValue().path("content");
                String label = operation.getKey() + " " + code;
                if (code.matches("[45]\\d\\d")) {
                    assertThat(content.path("application/json").path("schema").path("$ref").asText())
                            .as(label).isEqualTo(ERROR_REF);
                } else if (code.equals("204")) {
                    assertThat(content.isMissingNode()).as(label).isTrue();
                } else {
                    assertThat(fieldNames(content)).as(label).containsExactly("application/json");
                }
            });
        }

        assertThat(successSchema(paths, "/api/v1/metrics/{dbId}/latest", "get").path("$ref").asText())
                .isEqualTo("#/components/schemas/Metric");
        assertThat(successSchema(paths, "/api/v1/metrics/{dbId}/recent", "get").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/Metric");
        assertThat(successSchema(paths, "/api/v1/metrics/{dbId}/history", "get").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/Metric");
        assertThat(successSchema(paths, "/api/v1/databases/{id}/ping", "post").path("$ref").asText())
                .isEqualTo("#/components/schemas/PingResult");
        assertThat(paths.path("/api/v1/metrics/{dbId}/latest").path("get").path("responses").has("204")).isTrue();
        assertThat(paths.path("/api/v1/databases/{id}/ping").path("post").path("responses").has("403")).isTrue();
    }

    @Test
    @DisplayName("history start/end are required UTC date-times and recent limit is 1~1000 with default 50")
    void queryParametersMatchContract() throws Exception {
        JsonNode paths = api().path("paths");
        JsonNode history = paths.path("/api/v1/metrics/{dbId}/history").path("get");
        for (String name : List.of("start", "end")) {
            JsonNode parameter = parameter(history, name);
            assertThat(parameter.path("required").asBoolean()).as(name).isTrue();
            assertThat(parameter.path("schema").path("format").asText()).as(name).isEqualTo("date-time");
        }
        JsonNode limit = parameter(paths.path("/api/v1/metrics/{dbId}/recent").path("get"), "limit");
        assertThat(limit.path("required").asBoolean()).isFalse();
        assertThat(limit.path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(limit.path("schema").path("maximum").asInt()).isEqualTo(1000);
        assertThat(limit.path("schema").path("default").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("Metric schema lists every fixture field as required, and every field a fixture sets to null is nullable")
    void metricSchemaMatchesFixtures() throws Exception {
        JsonNode metric = api().path("components").path("schemas").path("Metric");
        JsonNode fixtures = objectMapper.readTree(Files.readString(CONTRACT_EXAMPLES)).get("fixtures");
        List<String> fields = fieldNames(fixtures.get("metricSuccess"));

        assertThat(fieldNames(metric.path("properties"))).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(textValues(metric.path("required"))).containsExactlyInAnyOrderElementsOf(fields);
        for (String fixture : List.of("metricSuccess", "metricWarmup", "metricFailure", "metricMeasuredZero",
                "metricPartialFailure")) {
            fixtures.get(fixture).fields().forEachRemaining(field -> {
                if (field.getValue().isNull()) {
                    assertThat(metric.path("properties").path(field.getKey()).path("nullable").asBoolean())
                            .as("%s.%s", fixture, field.getKey()).isTrue();
                }
            });
        }
        for (String alwaysPresent : List.of("id", "databaseConfigId", "configVersion", "timestamp",
                "collectionAttemptTime", "collectionStatus", "unavailableMetrics")) {
            assertThat(metric.path("properties").path(alwaysPresent).path("nullable").asBoolean())
                    .as(alwaysPresent).isFalse();
        }
        assertThat(textValues(metric.path("properties").path("collectionStatus").path("enum")))
                .containsExactlyInAnyOrder("SUCCESS", "PARTIAL_FAILURE", "CONNECTION_FAILED");
        assertThat(textValues(metric.path("properties").path("errorCode").path("enum"))).containsExactlyInAnyOrder(
                "AUTH_FAILED", "CONNECT_TIMEOUT", "CONNECTION_REFUSED", "QUERY_FAILED", "INTERNAL_ERROR", "UNKNOWN");
        assertThat(textValues(metric.path("properties").path("unavailableMetrics")
                .path("additionalProperties").path("enum"))).containsExactlyInAnyOrder(
                "UNSUPPORTED", "WARMUP", "COUNTER_RESET", "COLLECTION_FAILED", "QUERY_FAILED");
    }

    @Test
    @DisplayName("PingResult has the documented fields, UP/DOWN status and nullable version/error fields")
    void pingResultSchemaMatchesContract() throws Exception {
        JsonNode ping = api().path("components").path("schemas").path("PingResult");
        List<String> fields = List.of("databaseConfigId", "status", "version", "responseTimeMs", "timestamp",
                "errorCode", "errorMessage");

        assertThat(fieldNames(ping.path("properties"))).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(textValues(ping.path("required"))).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(textValues(ping.path("properties").path("status").path("enum"))).containsExactly("UP", "DOWN");
        for (String nullable : List.of("version", "errorCode", "errorMessage")) {
            assertThat(ping.path("properties").path(nullable).path("nullable").asBoolean()).as(nullable).isTrue();
        }
        assertThat(textValues(ping.path("properties").path("errorCode").path("enum")))
                .contains("AUTH_FAILED", "CONNECT_TIMEOUT", "CONNECTION_REFUSED", "QUERY_FAILED", "UNKNOWN");
    }

    private JsonNode api() throws Exception {
        if (api == null) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            api = objectMapper.readTree(response.body());
            Path export = Path.of("build", "reports", "part-a-openapi.json");
            Files.createDirectories(export.getParent());
            Files.writeString(export, response.body(), StandardCharsets.UTF_8);
        }
        return api;
    }

    private static JsonNode successSchema(JsonNode paths, String path, String method) {
        return paths.path(path).path(method).path("responses").path("200").path("content")
                .path("application/json").path("schema");
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) return parameter;
        }
        return MissingNode.getInstance();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }
}
