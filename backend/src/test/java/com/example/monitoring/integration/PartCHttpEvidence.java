package com.example.monitoring.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class PartCHttpEvidence {

    private static final List<Map<String, Object>> HTTP =
            Collections.synchronizedList(new ArrayList<>());
    private static final List<Map<String, Object>> SCENARIOS =
            Collections.synchronizedList(new ArrayList<>());

    private PartCHttpEvidence() {
    }

    static void recordHttp(ObjectMapper objectMapper, String method, String path,
                           HttpResponse<String> response) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("method", method);
        observation.put("path", path);
        observation.put("status", response.statusCode());
        observation.put("noStore", response.headers().firstValue("Cache-Control")
                .orElse("").contains("no-store"));
        observation.put("bodySha256", sha256(response.body()));
        if (response.body().isBlank()) {
            observation.put("bodyKind", "empty");
        } else {
            addJsonShape(objectMapper, response.body(), observation);
        }
        HTTP.add(Map.copyOf(observation));
    }

    static void recordScenario(String scenario, Map<String, ?> observables) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("scenario", scenario);
        observation.put("passed", true);
        observation.put("observables", Map.copyOf(observables));
        SCENARIOS.add(Map.copyOf(observation));
    }

    static void write(ObjectMapper objectMapper) throws IOException {
        Path report = Path.of("build", "reports", "part-c-http", "task-19-http-contract.json");
        Files.createDirectories(report.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "schemaVersion", 1,
                "httpObservations", List.copyOf(HTTP),
                "scenarioObservations", List.copyOf(SCENARIOS)));
    }

    private static void addJsonShape(ObjectMapper objectMapper, String body,
                                     Map<String, Object> observation) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json.isObject()) {
                observation.put("bodyKind", "object");
                TreeSet<String> fields = new TreeSet<>();
                json.fieldNames().forEachRemaining(fields::add);
                observation.put("topLevelFields", List.copyOf(fields));
                if (json.path("code").isTextual()) {
                    observation.put("errorCode", json.path("code").asText());
                }
            } else if (json.isArray()) {
                observation.put("bodyKind", "array");
                observation.put("arraySize", json.size());
            } else {
                observation.put("bodyKind", "scalar");
            }
        } catch (IOException ignored) {
            observation.put("bodyKind", "non-json");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
