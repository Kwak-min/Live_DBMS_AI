package com.example.monitoring.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class PartCNativeQaEvidence {
    private static final Pattern FILE_NAME = Pattern.compile("[a-z][a-z0-9-]*\\.json");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|secret|token|authorization|cookie|private.?key|p256dh|auth|url|endpoint|ciphertext).*"
    );
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private PartCNativeQaEvidence() {
    }

    public static void write(String fileName, Map<String, ?> values) throws IOException {
        if (!FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Invalid native QA evidence file name");
        }
        String configured = System.getenv("PART_C_NATIVE_QA_EVIDENCE_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("PART_C_NATIVE_QA_EVIDENCE_DIR is required");
        }
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || (SENSITIVE_KEY.matcher(key).matches()
                    && !(value instanceof Boolean))) {
                throw new IllegalArgumentException("Sensitive native QA evidence key is forbidden");
            }
            if (!(value instanceof Boolean || value instanceof Number || value instanceof String)) {
                throw new IllegalArgumentException("Native QA evidence values must be scalar");
            }
            sanitized.put(key, value);
        });

        Path root = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path destination = root.resolve(fileName).normalize();
        if (!destination.getParent().equals(root)) {
            throw new IllegalArgumentException("Native QA evidence path escaped its root");
        }
        Path temporary = Files.createTempFile(root, fileName + '.', ".tmp");
        try {
            byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(sanitized);
            Files.write(temporary, json);
            Files.writeString(temporary, System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
