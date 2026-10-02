package com.example.monitoring.realtime.redis;

import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class MetricPayloadParser {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern UTC_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "schemaVersion", "eventId", "eventType", "publishedAt", "metricId",
            "databaseConfigId", "configVersion", "databaseName", "timestamp",
            "collectionAttemptTime", "lastSuccessAt", "cpuUsage", "memoryUsage",
            "activeConnections", "maxConnections", "qps", "slowQueries",
            "slowQueriesDelta", "slowQueriesPerSecond", "metricWindowSeconds",
            "threadsRunning", "storageBytes", "responseTimeMs", "collectionStatus",
            "errorCode", "errorMessage", "unavailableMetrics");
    private static final Set<String> METRIC_FIELDS = Set.of(
            "cpuUsage", "memoryUsage", "activeConnections", "maxConnections", "qps",
            "slowQueries", "slowQueriesDelta", "slowQueriesPerSecond",
            "metricWindowSeconds", "threadsRunning", "storageBytes", "responseTimeMs");

    private final ObjectMapper mapper;

    public MetricPayloadParser(ObjectMapper objectMapper) {
        mapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public MetricCollectedPayloadV1 parse(byte[] utf8Payload) {
        if (utf8Payload.length > MAX_PAYLOAD_BYTES) {
            throw invalid("PAYLOAD_TOO_LARGE", "Metric payload exceeds 64 KiB", null);
        }
        String json = decodeUtf8(utf8Payload);
        JsonNode root = readObject(json);
        UUID eventId = optionalEventId(root);
        for (String field : REQUIRED_FIELDS) {
            if (!root.has(field)) {
                throw invalid("MISSING_REQUIRED_FIELD", "Required metric field is missing: " + field, eventId);
            }
        }

        int schemaVersion = exactInt(root, "schemaVersion", eventId);
        if (schemaVersion != MetricCollectedPayloadV1.SCHEMA_VERSION) {
            throw invalid("UNSUPPORTED_SCHEMA_VERSION", "Unsupported metric schema version", eventId);
        }
        String eventType = text(root, "eventType", false, eventId);
        if (!MetricCollectedPayloadV1.EVENT_TYPE.equals(eventType)) {
            throw invalid("UNSUPPORTED_EVENT_TYPE", "Unsupported metric event type", eventId);
        }

        Map<String, MetricCollectedPayloadV1.UnavailableReason> unavailable =
                unavailableMetrics(root.get("unavailableMetrics"), eventId);
        NumericFields metrics = numericFields(root, eventId);
        validateAvailability(root, unavailable, eventId);
        MetricCollectedPayloadV1.CollectionStatus status =
                enumValue(root, "collectionStatus", MetricCollectedPayloadV1.CollectionStatus.class, false, eventId);
        MetricCollectedPayloadV1.MetricErrorCode errorCode =
                enumValue(root, "errorCode", MetricCollectedPayloadV1.MetricErrorCode.class, true, eventId);
        if (status != MetricCollectedPayloadV1.CollectionStatus.SUCCESS && errorCode == null) {
            throw invalid("MISSING_REQUIRED_FIELD", "Failed collection requires errorCode", eventId);
        }

        return new MetricCollectedPayloadV1(
                schemaVersion,
                uuid(root, "eventId", eventId),
                eventType,
                time(root, "publishedAt", false, eventId),
                positiveId(root, "metricId", eventId),
                positiveId(root, "databaseConfigId", eventId),
                positiveId(root, "configVersion", eventId),
                text(root, "databaseName", false, eventId),
                time(root, "timestamp", false, eventId),
                time(root, "collectionAttemptTime", false, eventId),
                time(root, "lastSuccessAt", true, eventId),
                metrics.cpuUsage(),
                metrics.memoryUsage(),
                metrics.activeConnections(),
                metrics.maxConnections(),
                metrics.qps(),
                metrics.slowQueries(),
                metrics.slowQueriesDelta(),
                metrics.slowQueriesPerSecond(),
                metrics.metricWindowSeconds(),
                metrics.threadsRunning(),
                metrics.storageBytes(),
                metrics.responseTimeMs(),
                status,
                errorCode,
                text(root, "errorMessage", true, eventId),
                unavailable);
    }

    private NumericFields numericFields(JsonNode root, UUID eventId) {
        Double cpu = nullableDouble(root, "cpuUsage", eventId);
        Double memory = nullableDouble(root, "memoryUsage", eventId);
        if ((cpu != null && cpu > 100) || (memory != null && memory > 100)) {
            throw invalid("INVALID_NUMERIC_VALUE", "Usage percentage exceeds 100", eventId);
        }
        Double window = nullableDouble(root, "metricWindowSeconds", eventId);
        if (window != null && window <= 0) {
            throw invalid("INVALID_NUMERIC_VALUE", "metricWindowSeconds must be positive", eventId);
        }
        return new NumericFields(
                cpu, memory,
                nullableLong(root, "activeConnections", eventId),
                nullableLong(root, "maxConnections", eventId),
                nullableDouble(root, "qps", eventId),
                nullableLong(root, "slowQueries", eventId),
                nullableLong(root, "slowQueriesDelta", eventId),
                nullableDouble(root, "slowQueriesPerSecond", eventId),
                window,
                nullableLong(root, "threadsRunning", eventId),
                nullableLong(root, "storageBytes", eventId),
                nullableLong(root, "responseTimeMs", eventId));
    }

    private void validateAvailability(
            JsonNode root,
            Map<String, MetricCollectedPayloadV1.UnavailableReason> unavailable,
            UUID eventId
    ) {
        for (String field : METRIC_FIELDS) {
            boolean missingValue = root.get(field).isNull();
            if (missingValue != unavailable.containsKey(field)) {
                throw invalid("INVALID_FIELD_VALUE", "Metric availability does not match value: " + field, eventId);
            }
        }
    }

    private Map<String, MetricCollectedPayloadV1.UnavailableReason> unavailableMetrics(
            JsonNode node,
            UUID eventId
    ) {
        if (!node.isObject()) {
            throw invalid("INVALID_FIELD_TYPE", "unavailableMetrics must be an object", eventId);
        }
        Map<String, MetricCollectedPayloadV1.UnavailableReason> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!METRIC_FIELDS.contains(entry.getKey()) || !entry.getValue().isTextual()) {
                throw invalid("INVALID_ENUM_VALUE", "Invalid unavailable metric entry", eventId);
            }
            try {
                result.put(entry.getKey(),
                        MetricCollectedPayloadV1.UnavailableReason.valueOf(entry.getValue().textValue()));
            } catch (IllegalArgumentException exception) {
                throw invalid("INVALID_ENUM_VALUE", "Invalid unavailable metric reason", eventId);
            }
        });
        return result;
    }

    private JsonNode readObject(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalid("INVALID_JSON", "Metric payload must be a JSON object", null);
            }
            return root;
        } catch (JsonParseException exception) {
            String reason = exception.getOriginalMessage().contains("Duplicate field")
                    ? "DUPLICATE_JSON_KEY" : "INVALID_JSON";
            throw invalid(reason, "Metric payload is not valid JSON", null, exception);
        } catch (IOException exception) {
            throw invalid("INVALID_JSON", "Metric payload is not valid JSON", null, exception);
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("INVALID_UTF8", "Metric payload is not valid UTF-8", null, exception);
        }
    }

    private long positiveId(JsonNode root, String field, UUID eventId) {
        long value = exactLong(root, field, eventId);
        if (value < 1) {
            throw invalid("INVALID_NUMERIC_VALUE", field + " must be positive", eventId);
        }
        return value;
    }

    private int exactInt(JsonNode root, String field, UUID eventId) {
        long value = exactLong(root, field, eventId);
        if (value > Integer.MAX_VALUE) {
            throw invalid("INVALID_NUMERIC_VALUE", field + " is out of range", eventId);
        }
        return (int) value;
    }

    private Long nullableLong(JsonNode root, String field, UUID eventId) {
        return root.get(field).isNull() ? null : exactLong(root, field, eventId);
    }

    private long exactLong(JsonNode root, String field, UUID eventId) {
        JsonNode node = root.get(field);
        if (!node.isIntegralNumber()) {
            throw invalid("INVALID_FIELD_TYPE", field + " must be an integer", eventId);
        }
        BigInteger value = node.bigIntegerValue();
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) {
            throw invalid("INVALID_NUMERIC_VALUE", field + " is outside the safe range", eventId);
        }
        return value.longValue();
    }

    private Double nullableDouble(JsonNode root, String field, UUID eventId) {
        JsonNode node = root.get(field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid("INVALID_FIELD_TYPE", field + " must be numeric", eventId);
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < 0) {
            throw invalid("INVALID_NUMERIC_VALUE", field + " must be finite and non-negative", eventId);
        }
        return value;
    }

    private Instant time(JsonNode root, String field, boolean nullable, UUID eventId) {
        String value = text(root, field, nullable, eventId);
        if (value == null) {
            return null;
        }
        try {
            if (!UTC_TIME.matcher(value).matches()
                    || !OffsetDateTime.parse(value).getOffset().equals(ZoneOffset.UTC)) {
                throw new DateTimeParseException("Not canonical UTC", value, 0);
            }
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid("INVALID_TIMESTAMP", field + " must be canonical UTC time", eventId, exception);
        }
    }

    private UUID uuid(JsonNode root, String field, UUID eventId) {
        String value = text(root, field, false, eventId);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("UUID is not canonical lowercase");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid("INVALID_UUID", field + " must be a canonical lowercase UUID", eventId, exception);
        }
    }

    private UUID optionalEventId(JsonNode root) {
        if (!root.has("eventId") || !root.get("eventId").isTextual()) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(root.get("eventId").textValue());
            return parsed.toString().equals(root.get("eventId").textValue()) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String text(JsonNode root, String field, boolean nullable, UUID eventId) {
        JsonNode node = root.get(field);
        if (nullable && node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid("INVALID_FIELD_TYPE", field + " must be text", eventId);
        }
        return node.textValue();
    }

    private <E extends Enum<E>> E enumValue(
            JsonNode root,
            String field,
            Class<E> type,
            boolean nullable,
            UUID eventId
    ) {
        String value = text(root, field, nullable, eventId);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid("INVALID_ENUM_VALUE", "Invalid " + field, eventId, exception);
        }
    }

    private MetricPayloadException invalid(String code, String message, UUID eventId) {
        return new MetricPayloadException(code, message, eventId);
    }

    private MetricPayloadException invalid(String code, String message, UUID eventId, Throwable cause) {
        return new MetricPayloadException(code, message, eventId, cause);
    }

    private record NumericFields(
            Double cpuUsage,
            Double memoryUsage,
            Long activeConnections,
            Long maxConnections,
            Double qps,
            Long slowQueries,
            Long slowQueriesDelta,
            Double slowQueriesPerSecond,
            Double metricWindowSeconds,
            Long threadsRunning,
            Long storageBytes,
            Long responseTimeMs
    ) {
    }
}
