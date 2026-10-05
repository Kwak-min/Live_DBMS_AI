package com.example.monitoring.common.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamRecordProcessorTest {

    private static final byte[] PAYLOAD_KEY = bytes("payload");
    private static final UUID EVENT_ID = UUID.fromString("9bfab7ee-221a-4fb4-9507-6fd6f4df7e83");
    private static final Instant FAILED_AT = Instant.parse("2026-10-02T03:04:05.678Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final StreamWorkerSpec spec = new StreamWorkerSpec(
            "test:source", "cg:test", "test-worker", "test:dead-letter");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Long> delays = new ArrayList<>();
    private final StreamRetryBackoff backoff = new StreamRetryBackoff(delays::add);

    private RedisCommands<byte[], byte[]> commands;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        commands = mock(RedisCommands.class);
        when(commands.xadd(any(byte[].class), anyMap())).thenReturn("9-0");
    }

    @Test
    void acknowledgesOnlyAfterTheHandlerReturns() {
        List<String> order = new ArrayList<>();
        StreamRecordHandler handler = record -> order.add("committed");
        doAnswer(invocation -> {
            order.add("acknowledged");
            return 1L;
        }).when(commands).xack(any(byte[].class), any(byte[].class), eq("1-0"));

        processor(handler).process(message(validPayload()), commands, running::get, backoff);

        assertThat(order).containsExactly("committed", "acknowledged");
    }

    @Test
    void retriesInvariantFiveTimesThenWritesSanitizedDlqBeforeAck() throws Exception {
        StreamRecordHandler handler = record -> {
            throw new InvariantStreamRecordException("FUTURE_STATE_VERSION", "future", EVENT_ID);
        };
        ArgumentCaptor<Map<byte[], byte[]>> deadLetter = bodyCaptor();

        processor(handler).process(message(validPayload()), commands, running::get, backoff);

        verify(commands, times(1)).xadd(any(byte[].class), deadLetter.capture());
        verify(commands, times(1)).xack(any(byte[].class), any(byte[].class), eq("1-0"));
        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
        JsonNode body = mapper.readTree(deadLetterJson(deadLetter.getValue()));
        assertThat(body.path("reasonCode").asText()).isEqualTo("FUTURE_STATE_VERSION");
        assertThat(body.path("attemptCount").asInt()).isEqualTo(5);
        assertThat(body.path("eventId").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(body.path("failedAt").asText()).isEqualTo("2026-10-02T03:04:05.678Z");
        assertThat(body.path("payload").asText()).doesNotContain("secret-value");
        JsonNode sanitized = mapper.readTree(body.path("payload").asText());
        assertThat(sanitized.path("eventId").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(sanitized.path("eventType").asText())
                .isEqualTo("MonitoringStatusChangedEvent");
    }

    @Test
    void leavesDependencyFailurePendingWithoutDlqOrAck() {
        StreamRecordHandler handler = record -> {
            running.set(false);
            throw new IllegalStateException("database unavailable");
        };

        processor(handler).process(message(validPayload()), commands, running::get, backoff);

        verify(commands, never()).xadd(any(byte[].class), anyMap());
        verify(commands, never()).xack(any(byte[].class), any(byte[].class), any(String[].class));
        assertThat(delays).containsExactly(1_000L);
    }

    @Test
    void dlqFailureLeavesInvalidSourceRecordPending() {
        StreamRecordHandler handler = record -> {
            throw new InvalidStreamRecordException("INVALID_JSON", "invalid", EVENT_ID);
        };
        when(commands.xadd(any(byte[].class), anyMap()))
                .thenThrow(new RedisCommandExecutionException("WRONGTYPE"));

        assertThatThrownBy(() -> processor(handler)
                .process(message(validPayload()), commands, running::get, backoff))
                .isInstanceOf(RedisCommandExecutionException.class);

        verify(commands, never()).xack(any(byte[].class), any(byte[].class), any(String[].class));
    }

    @Test
    void missingDlqRecordIdLeavesInvalidSourceRecordPending() {
        StreamRecordHandler handler = record -> {
            throw new InvalidStreamRecordException("INVALID_JSON", "invalid", EVENT_ID);
        };
        when(commands.xadd(any(byte[].class), anyMap())).thenReturn(null);

        assertThatThrownBy(() -> processor(handler)
                .process(message(validPayload()), commands, running::get, backoff))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dead-letter record id");

        verify(commands, never()).xack(any(byte[].class), any(byte[].class), any(String[].class));
    }

    @Test
    void malformedRecordShapeIsDlqThenAckWithoutInvokingHandler() throws Exception {
        StreamRecordHandler handler = mock(StreamRecordHandler.class);
        Map<byte[], byte[]> malformed = new LinkedHashMap<>();
        malformed.put(PAYLOAD_KEY, validPayload());
        malformed.put(bytes("unexpected"), bytes("unsafe"));
        ArgumentCaptor<Map<byte[], byte[]>> deadLetter = bodyCaptor();

        processor(handler).process(message(malformed), commands, running::get, backoff);

        verify(handler, never()).handle(any());
        verify(commands).xadd(any(byte[].class), deadLetter.capture());
        verify(commands).xack(any(byte[].class), any(byte[].class), eq("1-0"));
        JsonNode body = mapper.readTree(deadLetterJson(deadLetter.getValue()));
        assertThat(body.path("reasonCode").asText()).isEqualTo("INVALID_RECORD_SHAPE");
    }

    @Test
    void unknownEventTypeCannotSurviveSanitizedDlqProjection() throws Exception {
        String secretSentinel = "SecretSentinelEventType42";
        byte[] payload = bytes(new String(validPayload(), StandardCharsets.UTF_8)
                .replace("MonitoringStatusChangedEvent", secretSentinel));
        StreamRecordHandler handler = record -> {
            throw new InvalidStreamRecordException("UNSUPPORTED_EVENT_TYPE", "invalid", EVENT_ID);
        };
        ArgumentCaptor<Map<byte[], byte[]>> deadLetter = bodyCaptor();

        processor(handler).process(message(payload), commands, running::get, backoff);

        verify(commands).xadd(any(byte[].class), deadLetter.capture());
        JsonNode body = mapper.readTree(deadLetterJson(deadLetter.getValue()));
        JsonNode sanitized = mapper.readTree(body.path("payload").asText());
        assertThat(sanitized.has("eventType")).isFalse();
        assertThat(body.path("payload").asText()).doesNotContain(secretSentinel);
    }

    private RedisStreamRecordProcessor processor(StreamRecordHandler handler) {
        return new RedisStreamRecordProcessor(
                spec,
                handler,
                mapper,
                Clock.fixed(FAILED_AT, ZoneOffset.UTC));
    }

    private StreamMessage<byte[], byte[]> message(byte[] payload) {
        return message(Map.of(PAYLOAD_KEY, payload));
    }

    @SuppressWarnings("unchecked")
    private StreamMessage<byte[], byte[]> message(Map<byte[], byte[]> body) {
        StreamMessage<byte[], byte[]> message = mock(StreamMessage.class);
        when(message.getId()).thenReturn("1-0");
        when(message.getBody()).thenReturn(body);
        return message;
    }

    private byte[] validPayload() {
        return bytes("""
                {"schemaVersion":1,"eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "eventType":"MonitoringStatusChangedEvent","publishedAt":"2026-10-02T03:00:00.000Z",
                 "databaseConfigId":12,"configVersion":2,"stateVersion":8,
                 "databasePassword":"secret-value"}
                """);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Map<byte[], byte[]>> bodyCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private String deadLetterJson(Map<byte[], byte[]> body) {
        byte[] payload = body.entrySet().stream()
                .filter(entry -> Arrays.equals(entry.getKey(), PAYLOAD_KEY))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
