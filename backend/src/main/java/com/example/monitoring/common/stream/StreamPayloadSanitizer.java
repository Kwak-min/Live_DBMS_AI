package com.example.monitoring.common.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class StreamPayloadSanitizer {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final String INVALID_UTF8 = "[INVALID_UTF8]";
    private static final String UNPARSEABLE_JSON = "[UNPARSEABLE_JSON]";
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> KNOWN_EVENT_TYPES = Set.of(
            "MetricCollectedEvent",
            "MonitoringStatusChangedEvent",
            "IncidentCreatedEvent",
            "IncidentUpdatedEvent",
            "IncidentResolvedEvent",
            "CollectorHeartbeatEvent");
    private static final List<String> SAFE_POSITIVE_IDS = List.of(
            "metricId",
            "databaseConfigId",
            "configVersion",
            "stateVersion",
            "incidentVersion");
    private static final List<String> SAFE_UUIDS = List.of(
            "eventId",
            "incidentId",
            "sourceEventId");

    private final ObjectMapper mapper;

    StreamPayloadSanitizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String sanitize(byte[] payload) {
        String decoded = decode(payload);
        if (INVALID_UTF8.equals(decoded)) {
            return decoded;
        }
        try {
            JsonNode root = mapper.readTree(decoded);
            if (root == null || !root.isObject()) {
                return UNPARSEABLE_JSON;
            }
            return truncateUtf8(mapper.writeValueAsString(project(root)), MAX_PAYLOAD_BYTES);
        } catch (JsonProcessingException ignored) {
            return UNPARSEABLE_JSON;
        }
    }

    private ObjectNode project(JsonNode source) {
        ObjectNode result = mapper.createObjectNode();
        copyInteger(source, result, "schemaVersion", false);
        SAFE_UUIDS.forEach(field -> copyUuid(source, result, field));
        copyEventType(source, result);
        copyTime(source, result, "publishedAt");
        SAFE_POSITIVE_IDS.forEach(field -> copyInteger(source, result, field, true));
        return result;
    }

    private void copyInteger(JsonNode source, ObjectNode target, String field, boolean positive) {
        JsonNode value = source.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return;
        }
        BigInteger integer = value.bigIntegerValue();
        int minimumSign = positive ? 1 : 0;
        if (integer.signum() >= minimumSign && integer.compareTo(MAX_SAFE_INTEGER) <= 0) {
            target.set(field, value);
        }
    }

    private void copyUuid(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null || !value.isTextual()) {
            return;
        }
        try {
            UUID parsed = UUID.fromString(value.textValue());
            if (parsed.toString().equals(value.textValue())) {
                target.put(field, value.textValue());
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void copyEventType(JsonNode source, ObjectNode target) {
        JsonNode value = source.get("eventType");
        if (value != null && value.isTextual()
                && KNOWN_EVENT_TYPES.contains(value.textValue())) {
            target.put("eventType", value.textValue());
        }
    }

    private void copyTime(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null || !value.isTextual()) {
            return;
        }
        try {
            Instant parsed = Instant.parse(value.textValue());
            if (UTC_MILLIS.format(parsed).equals(value.textValue())) {
                target.put(field, value.textValue());
            }
        } catch (DateTimeParseException ignored) {
        }
    }

    private String decode(byte[] payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException exception) {
            return INVALID_UTF8;
        }
    }

    private String truncateUtf8(String value, int maximumBytes) {
        StringBuilder result = new StringBuilder(value.length());
        int byteCount = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + characterBytes > maximumBytes) {
                break;
            }
            result.append(character);
            byteCount += characterBytes;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
