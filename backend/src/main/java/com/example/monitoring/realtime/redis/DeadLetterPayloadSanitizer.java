package com.example.monitoring.realtime.redis;

import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
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
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.UUID;

final class DeadLetterPayloadSanitizer {

    private static final String INVALID_UTF8 = "[INVALID_UTF8]";
    private static final String UNPARSEABLE_JSON = "[UNPARSEABLE_JSON]";
    private static final BigInteger MAX_SAFE_INTEGER =
            BigInteger.valueOf(9_007_199_254_740_991L);
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final List<String> POSITIVE_INTEGER_FIELDS = List.of(
            "metricId", "databaseConfigId", "configVersion");
    private final ObjectMapper mapper;

    DeadLetterPayloadSanitizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String sanitize(byte[] payload) {
        String decoded = decode(payload);
        if (INVALID_UTF8.equals(decoded)) {
            return decoded;
        }
        String sanitized = sanitizeJson(decoded);
        return truncateUtf8(sanitized, MetricPayloadParser.MAX_PAYLOAD_BYTES);
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return INVALID_UTF8;
        }
    }

    private String sanitizeJson(String decoded) {
        try {
            JsonNode root = mapper.readTree(decoded);
            if (root == null || !root.isObject()) {
                return UNPARSEABLE_JSON;
            }
            return mapper.writeValueAsString(projectDiagnostics(root));
        } catch (JsonProcessingException ignored) {
            return UNPARSEABLE_JSON;
        }
    }

    private ObjectNode projectDiagnostics(JsonNode root) {
        ObjectNode result = mapper.createObjectNode();
        copyInteger(root, result, "schemaVersion", false);
        copyCanonicalUuid(root, result, "eventId");
        copyKnownText(root, result, "eventType", MetricCollectedPayloadV1.EVENT_TYPE);
        copyCanonicalTime(root, result, "publishedAt", false);
        POSITIVE_INTEGER_FIELDS.forEach(field -> copyInteger(root, result, field, true));
        copyCanonicalTime(root, result, "timestamp", false);
        copyCanonicalTime(root, result, "collectionAttemptTime", false);
        copyCanonicalTime(root, result, "lastSuccessAt", true);
        copyEnum(root, result, "collectionStatus",
                MetricCollectedPayloadV1.CollectionStatus.class, false);
        copyEnum(root, result, "errorCode",
                MetricCollectedPayloadV1.MetricErrorCode.class, true);
        return result;
    }

    private void copyInteger(
            JsonNode source,
            ObjectNode target,
            String field,
            boolean positive
    ) {
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

    private boolean copyNull(ObjectNode target, String field, JsonNode value, boolean nullable) {
        if (value != null && value.isNull() && nullable) {
            target.putNull(field);
            return true;
        }
        return false;
    }

    private void copyCanonicalUuid(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null || !value.isTextual()) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(value.textValue());
            if (uuid.toString().equals(value.textValue())) {
                target.put(field, value.textValue());
            }
        } catch (IllegalArgumentException ignored) {
            // Unsafe diagnostic values are omitted.
        }
    }

    private void copyCanonicalTime(
            JsonNode source,
            ObjectNode target,
            String field,
            boolean nullable
    ) {
        JsonNode value = source.get(field);
        if (copyNull(target, field, value, nullable) || value == null || !value.isTextual()) {
            return;
        }
        try {
            Instant parsed = Instant.parse(value.textValue());
            if (UTC_MILLIS.format(parsed).equals(value.textValue())) {
                target.put(field, value.textValue());
            }
        } catch (DateTimeParseException ignored) {
            // Unsafe diagnostic values are omitted.
        }
    }

    private void copyKnownText(
            JsonNode source,
            ObjectNode target,
            String field,
            String expected
    ) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && expected.equals(value.textValue())) {
            target.put(field, expected);
        }
    }

    private <E extends Enum<E>> void copyEnum(
            JsonNode source,
            ObjectNode target,
            String field,
            Class<E> enumType,
            boolean nullable
    ) {
        JsonNode value = source.get(field);
        if (copyNull(target, field, value, nullable) || value == null || !value.isTextual()) {
            return;
        }
        try {
            Enum.valueOf(enumType, value.textValue());
            target.put(field, value.textValue());
        } catch (IllegalArgumentException ignored) {
            // Unsafe diagnostic values are omitted.
        }
    }

    private String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder(value.length());
        int bytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            bytes += characterBytes;
            index += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
