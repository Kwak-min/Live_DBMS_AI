package com.example.monitoring.common.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class RedisStreamRecordProcessor {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamRecordProcessor.class);
    static final int MAX_INVARIANT_ATTEMPTS = 5;
    private static final byte[] PAYLOAD_KEY = "payload".getBytes(StandardCharsets.UTF_8);
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final StreamWorkerSpec spec;
    private final StreamRecordHandler handler;
    private final ObjectMapper mapper;
    private final StreamPayloadSanitizer sanitizer;
    private final Clock clock;

    RedisStreamRecordProcessor(
            StreamWorkerSpec spec,
            StreamRecordHandler handler,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.spec = spec;
        this.handler = handler;
        this.mapper = mapper;
        this.sanitizer = new StreamPayloadSanitizer(mapper);
        this.clock = clock;
    }

    void process(
            StreamMessage<byte[], byte[]> message,
            RedisCommands<byte[], byte[]> commands,
            BooleanSupplier running,
            StreamRetryBackoff backoff
    ) {
        byte[] payload;
        try {
            payload = extractPayload(message);
        } catch (InvalidStreamRecordException exception) {
            reject(
                    commands,
                    message,
                    payloadForRejectedShape(message),
                    exception.eventId(),
                    exception.reasonCode(),
                    1);
            backoff.reset();
            return;
        }

        StreamRecord record = new StreamRecord(spec.sourceStream(), message.getId(), payload);
        int invariantAttempts = 0;
        while (running.getAsBoolean()) {
            try {
                handler.handle(record);
                acknowledge(commands, message.getId());
                backoff.reset();
                return;
            } catch (InvalidStreamRecordException exception) {
                reject(
                        commands,
                        message,
                        payload,
                        exception.eventId(),
                        exception.reasonCode(),
                        1);
                backoff.reset();
                return;
            } catch (InvariantStreamRecordException exception) {
                invariantAttempts++;
                if (invariantAttempts >= MAX_INVARIANT_ATTEMPTS) {
                    reject(
                            commands,
                            message,
                            payload,
                            exception.eventId(),
                            exception.reasonCode(),
                            invariantAttempts);
                    backoff.reset();
                    return;
                }
                backoff.pause();
            } catch (RedisException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "Part C stream handoff failed; record remains pending: stream={}, group={}, recordId={}, cause={}",
                        spec.sourceStream(),
                        spec.consumerGroup(),
                        message.getId(),
                        exception.getClass().getSimpleName());
                backoff.pause();
            }
        }
    }

    private byte[] extractPayload(StreamMessage<byte[], byte[]> message) {
        if (message.getBody().size() != 1) {
            throw invalidShape();
        }
        Map.Entry<byte[], byte[]> entry = message.getBody().entrySet().iterator().next();
        if (!Arrays.equals(entry.getKey(), PAYLOAD_KEY) || entry.getValue() == null) {
            throw invalidShape();
        }
        return entry.getValue();
    }

    private InvalidStreamRecordException invalidShape() {
        return new InvalidStreamRecordException(
                "INVALID_RECORD_SHAPE",
                "Redis stream record must contain exactly one payload field",
                null);
    }

    private void acknowledge(RedisCommands<byte[], byte[]> commands, String recordId) {
        commands.xack(bytes(spec.sourceStream()), bytes(spec.consumerGroup()), recordId);
    }

    private void reject(
            RedisCommands<byte[], byte[]> commands,
            StreamMessage<byte[], byte[]> message,
            byte[] rawPayload,
            UUID eventId,
            String reasonCode,
            int attemptCount
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceStream", spec.sourceStream());
        body.put("recordId", message.getId());
        body.put("eventId", eventId == null ? null : eventId.toString());
        body.put("reasonCode", reasonCode);
        body.put("failedAt", UTC_MILLIS.format(clock.instant()));
        body.put("attemptCount", attemptCount);
        body.put("payload", sanitizer.sanitize(rawPayload));
        String deadLetterId = commands.xadd(
                bytes(spec.deadLetterStream()),
                Map.of(PAYLOAD_KEY, writeJson(body)));
        if (deadLetterId == null) {
            throw new IllegalStateException("Redis XADD returned no dead-letter record id");
        }
        acknowledge(commands, message.getId());
    }

    private byte[] payloadForRejectedShape(StreamMessage<byte[], byte[]> message) {
        return message.getBody().entrySet().stream()
                .filter(entry -> Arrays.equals(entry.getKey(), PAYLOAD_KEY))
                .map(Map.Entry::getValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(() -> new byte[0]);
    }

    private byte[] writeJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize dead-letter metadata", exception);
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
