package com.example.monitoring.common.stream;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class StrictEventFields {

    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final ObjectMapper mapper;

    public StrictEventFields(ObjectMapper objectMapper) {
        mapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ObjectNode parseObject(byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
            throw new InvalidStreamRecordException(
                    "PAYLOAD_TOO_LARGE", "Event payload exceeds 64 KiB", null);
        }
        String decoded = decode(payload);
        try (JsonParser parser = mapper.getFactory().createParser(decoded)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new InvalidStreamRecordException(
                        "INVALID_JSON", "Event payload must be a JSON object", null);
            }
            ObjectNode root = readObject(parser);
            if (parser.nextToken() != null) {
                throw new InvalidStreamRecordException(
                        "INVALID_JSON", "Event payload contains trailing content", null);
            }
            return root;
        } catch (JsonParseException exception) {
            String reason = exception.getOriginalMessage().contains("Duplicate field")
                    ? "DUPLICATE_JSON_KEY" : "INVALID_JSON";
            throw new InvalidStreamRecordException(reason, "Event payload is not valid JSON", null, exception);
        } catch (NumberFormatException exception) {
            throw new InvalidStreamRecordException(
                    "INVALID_NUMERIC_VALUE", "Event payload contains an unrepresentable number", null, exception);
        } catch (IOException exception) {
            throw new InvalidStreamRecordException(
                    "INVALID_JSON", "Event payload is not valid JSON", null, exception);
        }
    }

    public void requireFields(ObjectNode root, String... fields) {
        for (String field : fields) {
            if (!root.has(field)) {
                throw invalid(root, "MISSING_REQUIRED_FIELD", "Required event field is missing: " + field);
            }
        }
    }

    public int requiredInt(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be an integer");
        }
        return value.intValue();
    }

    public long requiredSafeLong(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be an integer");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.signum() <= 0 || integer.compareTo(MAX_SAFE_INTEGER) > 0) {
            throw invalid(root, "INVALID_NUMERIC_VALUE", field + " is outside the safe positive range");
        }
        return integer.longValueExact();
    }

    public Long requiredNullableSafeLong(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        return value.isNull() ? null : requiredSafeLong(root, field);
    }

    public boolean requiredBoolean(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isBoolean()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be boolean");
        }
        return value.booleanValue();
    }

    public String requiredText(ObjectNode root, String field, int minimumLength, int maximumLength) {
        JsonNode value = required(root, field);
        if (!value.isTextual()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be text");
        }
        String text = value.textValue();
        int codePointLength = text.codePointCount(0, text.length());
        if (codePointLength < minimumLength || codePointLength > maximumLength) {
            throw invalid(root, "INVALID_FIELD_VALUE", field + " length is invalid");
        }
        return text;
    }

    public String requiredNullableText(
            ObjectNode root,
            String field,
            int minimumLength,
            int maximumLength
    ) {
        JsonNode value = required(root, field);
        return value.isNull() ? null : requiredText(root, field, minimumLength, maximumLength);
    }

    public UUID requiredUuid(ObjectNode root, String field) {
        String value = requiredText(root, field, 36, 36);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("UUID is not canonical lowercase");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid(root, "INVALID_UUID", field + " must be a canonical lowercase UUID", exception);
        }
    }

    public UUID requiredNullableUuid(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        return value.isNull() ? null : requiredUuid(root, field);
    }

    public Instant requiredInstant(ObjectNode root, String field) {
        JsonNode node = required(root, field);
        if (!node.isTextual()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be text");
        }
        String value = node.textValue();
        try {
            if (value.length() != 24) {
                throw new DateTimeParseException("Not canonical millisecond UTC", value, 0);
            }
            Instant parsed = Instant.parse(value);
            if (!UTC_MILLIS.format(parsed).equals(value)) {
                throw new DateTimeParseException("Not canonical millisecond UTC", value, 0);
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid(root, "INVALID_TIMESTAMP", field + " must be canonical millisecond UTC", exception);
        }
    }

    public Instant requiredNullableInstant(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        return value.isNull() ? null : requiredInstant(root, field);
    }

    public BigDecimal requiredNullableNonNegativeDecimal(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be numeric");
        }
        BigDecimal decimal;
        try {
            decimal = value.decimalValue();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(root, "INVALID_NUMERIC_VALUE", field + " must be finite", exception);
        }
        if (decimal.signum() < 0) {
            throw invalid(root, "INVALID_NUMERIC_VALUE", field + " must be non-negative");
        }
        return decimal;
    }

    public <E extends Enum<E>> E requiredEnum(ObjectNode root, String field, Class<E> enumType) {
        String value = requiredText(root, field, 1, 64);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(root, "INVALID_ENUM_VALUE", "Invalid " + field, exception);
        }
    }

    public <E extends Enum<E>> E requiredNullableEnum(
            ObjectNode root,
            String field,
            Class<E> enumType
    ) {
        JsonNode value = required(root, field);
        return value.isNull() ? null : requiredEnum(root, field, enumType);
    }

    public ArrayNode requiredArray(ObjectNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isArray()) {
            throw invalid(root, "INVALID_FIELD_TYPE", field + " must be an array");
        }
        return (ArrayNode) value;
    }

    public UUID bestEffortEventId(ObjectNode root) {
        JsonNode value = root == null ? null : root.get("eventId");
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value.textValue());
            return parsed.toString().equals(value.textValue()) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public InvalidStreamRecordException invalid(ObjectNode root, String reasonCode, String message) {
        return new InvalidStreamRecordException(reasonCode, message, bestEffortEventId(root));
    }

    public InvalidStreamRecordException invalid(
            ObjectNode root,
            String reasonCode,
            String message,
            Throwable cause
    ) {
        return new InvalidStreamRecordException(reasonCode, message, bestEffortEventId(root), cause);
    }

    private ObjectNode readObject(JsonParser parser) throws IOException {
        ObjectNode result = mapper.createObjectNode();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null || token != JsonToken.FIELD_NAME) {
                throw new InvalidStreamRecordException(
                        "INVALID_JSON", "Event payload contains an invalid object", null);
            }
            String field = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw new InvalidStreamRecordException(
                        "INVALID_JSON", "Event payload contains an incomplete object", null);
            }
            result.set(field, readValue(parser, valueToken));
        }
        return result;
    }

    private ArrayNode readArray(JsonParser parser) throws IOException {
        ArrayNode result = mapper.createArrayNode();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw new InvalidStreamRecordException(
                        "INVALID_JSON", "Event payload contains an incomplete array", null);
            }
            result.add(readValue(parser, token));
        }
        return result;
    }

    private JsonNode readValue(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> mapper.getNodeFactory().textNode(parser.getText());
            case VALUE_NUMBER_INT -> mapper.getNodeFactory().numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> mapper.getNodeFactory()
                    .numberNode(new BigDecimal(parser.getText()));
            case VALUE_TRUE -> mapper.getNodeFactory().booleanNode(true);
            case VALUE_FALSE -> mapper.getNodeFactory().booleanNode(false);
            case VALUE_NULL -> mapper.getNodeFactory().nullNode();
            default -> throw new InvalidStreamRecordException(
                    "INVALID_JSON", "Event payload contains an invalid value", null);
        };
    }

    private JsonNode required(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            throw invalid(root, "MISSING_REQUIRED_FIELD", "Required event field is missing: " + field);
        }
        return value;
    }

    private String decode(byte[] payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidStreamRecordException(
                    "INVALID_UTF8", "Event payload is not valid UTF-8", null, exception);
        }
    }
}
