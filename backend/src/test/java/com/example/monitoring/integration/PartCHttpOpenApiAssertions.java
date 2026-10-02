package com.example.monitoring.integration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

final class PartCHttpOpenApiAssertions {

    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;

    private final JsonNode api;

    PartCHttpOpenApiAssertions(JsonNode api) {
        this.api = api;
    }

    void operation(String path, String method, String... statuses) {
        JsonNode operation = api.path("paths").path(path).path(method);
        assertThat(operation.isObject()).as("%s %s", method, path).isTrue();
        assertThat(operation.path("security").isArray()).as("%s %s security", method, path).isTrue();
        assertThat(operation.path("security").toString()).contains("\"bearerAuth\"");
        for (String status : statuses) {
            assertThat(operation.path("responses").has(status))
                    .as("%s %s response %s", method, path, status).isTrue();
        }
    }

    void errorSchemas() {
        api.path("paths").fields().forEachRemaining(path -> {
            if (!isPartCPath(path.getKey())) return;
            path.getValue().fields().forEachRemaining(operation -> {
                JsonNode responses = operation.getValue().path("responses");
                responses.fields().forEachRemaining(response -> {
                    if (!response.getKey().matches("[45][0-9][0-9]")) return;
                    JsonNode schema = response.getValue().path("content")
                            .path("application/json").path("schema");
                    assertThat(schema.path("$ref").asText())
                            .as("%s %s %s", operation.getKey(), path.getKey(), response.getKey())
                            .isEqualTo("#/components/schemas/ApiErrorResponse");
                });
            });
        });
    }

    JsonNode successSchema(String path, String method, String status) {
        JsonNode schema = api.path("paths").path(path).path(method).path("responses").path(status)
                .path("content").path("application/json").path("schema");
        assertThat(schema.isMissingNode()).as("%s %s %s schema", method, path, status).isFalse();
        return schema;
    }

    JsonNode requestSchema(String path, String method) {
        JsonNode schema = api.path("paths").path(path).path(method).path("requestBody")
                .path("content").path("application/json").path("schema");
        assertThat(schema.isMissingNode()).as("%s %s request schema", method, path).isFalse();
        return schema;
    }

    void noContentResponse(String path, String method) {
        JsonNode response = api.path("paths").path(path).path(method).path("responses").path("204");
        assertThat(response.isObject()).as("%s %s 204 response", method, path).isTrue();
        assertThat(response.path("content").isMissingNode())
                .as("%s %s 204 response body", method, path).isTrue();
    }

    JsonNode resolvedSchema(JsonNode schema) {
        JsonNode current = schema;
        for (int depth = 0; depth < 8 && current.has("$ref"); depth++) {
            String reference = current.path("$ref").asText();
            assertThat(reference).startsWith("#/components/schemas/");
            current = api.path("components").path("schemas")
                    .path(reference.substring("#/components/schemas/".length()));
        }
        assertThat(current.isObject()).isTrue();
        return current;
    }

    JsonNode pageItemSchema(JsonNode pageSchema) {
        JsonNode page = resolvedSchema(pageSchema);
        Set<String> pageFields = new LinkedHashSet<>();
        page.path("properties").fieldNames().forEachRemaining(pageFields::add);
        assertThat(pageFields)
                .containsExactlyInAnyOrder("items", "page", "size", "totalElements", "totalPages");
        JsonNode items = resolvedSchema(page.path("properties").path("items"));
        assertThat(items.path("type").asText()).isEqualTo("array");
        return items.path("items");
    }

    void schemaFields(JsonNode schema, Set<String> expectedFields) {
        JsonNode resolved = resolvedSchema(schema);
        JsonNode properties = resolved.path("properties");
        assertThat(properties.isObject()).isTrue();
        Set<String> names = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(expectedFields);
    }

    void requiredFields(JsonNode schema, Set<String> expectedFields) {
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(value -> required.add(value.asText()));
        assertThat(required).containsExactlyInAnyOrderElementsOf(expectedFields);
    }

    JsonNode parameter(String path, String method, String name) {
        for (JsonNode parameter : api.path("paths").path(path).path(method).path("parameters")) {
            if (name.equals(parameter.path("name").asText())) return parameter;
        }
        throw new AssertionError("Missing OpenAPI parameter " + method + " " + path + " " + name);
    }

    void queryParameter(String path, String name, String type, String format,
                        String minimum, String maximum, String defaultValue,
                        Set<String> enumValues) {
        JsonNode parameter = parameter(path, "get", name);
        assertThat(parameter.path("in").asText()).isEqualTo("query");
        JsonNode schema = parameter.path("schema");
        assertThat(schema.path("type").asText()).isEqualTo(type);
        if (format != null) assertThat(schema.path("format").asText()).isEqualTo(format);
        if (minimum != null) assertThat(schema.path("minimum").asText()).isEqualTo(minimum);
        if (maximum != null) assertThat(schema.path("maximum").asText()).isEqualTo(maximum);
        if (defaultValue != null) assertThat(schema.path("default").asText()).isEqualTo(defaultValue);
        if (!enumValues.isEmpty()) {
            List<String> actualEnums = new ArrayList<>();
            schema.path("enum").forEach(value -> actualEnums.add(value.asText()));
            assertThat(actualEnums).containsExactlyInAnyOrderElementsOf(enumValues);
        }
    }

    void pathIdParameter(String path, String method, String name) {
        JsonNode id = parameter(path, method, name);
        assertThat(id.path("in").asText()).isEqualTo("path");
        assertThat(id.path("required").asBoolean()).isTrue();
        JsonNode schema = id.path("schema");
        assertThat(schema.path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("format").asText()).isEqualTo("int64");
        assertThat(schema.path("minimum").asText()).isEqualTo("1");
        assertThat(schema.path("maximum").asText()).isEqualTo(Long.toString(MAX_SAFE_ID));
    }

    private boolean isPartCPath(String path) {
        return path.equals("/api/v1/databases/{id}/status")
                || path.equals("/api/v1/databases/{id}/risk-policy")
                || path.equals("/api/v1/incidents")
                || path.equals("/api/v1/incidents/{incidentId}")
                || path.startsWith("/api/v1/notifications/");
    }
}
