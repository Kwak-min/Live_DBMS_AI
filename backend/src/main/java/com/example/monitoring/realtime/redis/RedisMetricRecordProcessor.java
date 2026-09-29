package com.example.monitoring.realtime.redis;

import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
final class RedisMetricRecordProcessor {

    private static final byte[] PAYLOAD_KEY = "payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] GROUP = "cg:realtime".getBytes(StandardCharsets.UTF_8);
    private static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder()
            .appendInstant(3)
            .toFormatter();

    private final MetricPayloadParser parser;
    private final RealtimeMetricTransaction transaction;
    private final RealtimeRedisSettings settings;
    private final ObjectMapper objectMapper;
    private final DeadLetterPayloadSanitizer sanitizer;
    private final Clock clock;

    @Autowired
    RedisMetricRecordProcessor(
            MetricPayloadParser parser,
            RealtimeMetricTransaction transaction,
            RealtimeRedisSettings settings,
            ObjectMapper objectMapper
    ) {
        this(parser, transaction, settings, objectMapper, Clock.systemUTC());
    }

    RedisMetricRecordProcessor(
            MetricPayloadParser parser,
            RealtimeMetricTransaction transaction,
            RealtimeRedisSettings settings,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.parser = parser;
        this.transaction = transaction;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.sanitizer = new DeadLetterPayloadSanitizer(objectMapper);
        this.clock = clock;
    }

    byte[] extractPayload(StreamMessage<byte[], byte[]> message) {
        if (message.getBody().size() != 1) {
            throw new MetricPayloadException(
                    "INVALID_RECORD_SHAPE",
                    "Redis metric record must contain exactly one payload field",
                    null);
        }
        Map.Entry<byte[], byte[]> entry = message.getBody().entrySet().iterator().next();
        if (!java.util.Arrays.equals(entry.getKey(), PAYLOAD_KEY) || entry.getValue() == null) {
            throw new MetricPayloadException(
                    "INVALID_RECORD_SHAPE",
                    "Redis metric record must contain exactly one payload field",
                    null);
        }
        return entry.getValue();
    }

    MetricCollectedPayloadV1 parse(byte[] payload) {
        return parser.parse(payload);
    }

    RealtimeMetricTransaction.Outcome transact(MetricCollectedPayloadV1 payload) {
        return transaction.process(settings.sourceStream(), payload);
    }

    void acknowledge(RedisCommands<byte[], byte[]> commands, String recordId) {
        commands.xack(sourceStream(), GROUP, recordId);
    }

    void reject(
            RedisCommands<byte[], byte[]> commands,
            StreamMessage<byte[], byte[]> message,
            byte[] rawPayload,
            UUID eventId,
            String reasonCode,
            int attemptCount
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceStream", settings.sourceStream());
        body.put("recordId", message.getId());
        body.put("eventId", eventId == null ? null : eventId.toString());
        body.put("reasonCode", reasonCode);
        body.put("failedAt", UTC_MILLIS.format(clock.instant()));
        body.put("attemptCount", attemptCount);
        body.put("payload", sanitizer.sanitize(rawPayload));
        commands.xadd(deadLetterStream(), Map.of(PAYLOAD_KEY, writeJson(body)));
        acknowledge(commands, message.getId());
    }

    byte[] payloadForRejectedShape(StreamMessage<byte[], byte[]> message) {
        return message.getBody().entrySet().stream()
                .filter(entry -> java.util.Arrays.equals(entry.getKey(), PAYLOAD_KEY))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> new byte[0]);
    }

    private byte[] writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize dead-letter metadata", exception);
        }
    }

    private byte[] sourceStream() {
        return settings.sourceStream().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] deadLetterStream() {
        return settings.deadLetterStream().getBytes(StandardCharsets.UTF_8);
    }
}
