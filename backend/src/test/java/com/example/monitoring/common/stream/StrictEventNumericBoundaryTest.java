package com.example.monitoring.common.stream;

import com.example.monitoring.realtime.status.RealtimeStatusTransaction;
import com.example.monitoring.realtime.status.StatusStreamEventParser;
import com.example.monitoring.realtime.status.StatusStreamRecordHandler;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrictEventNumericBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"future\":1e2147483648}",
            "{\"future\":1e-2147483649}",
            "{\"future\":{\"nested\":[1e2147483648]}}"
    })
    void rejectsUnrepresentableDecimalBeforeFieldConversion(String json) {
        StrictEventFields fields = new StrictEventFields(mapper);

        assertThatThrownBy(() -> fields.parseObject(bytes(json)))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class, exception -> {
                    assertThat(exception.reasonCode()).isEqualTo("INVALID_NUMERIC_VALUE");
                    assertThat(exception.getCause()).isInstanceOf(NumberFormatException.class);
                });
    }

    @Test
    void acceptsLargeRepresentableDecimalWithoutIntegerConversion() {
        StrictEventFields fields = new StrictEventFields(mapper);

        JsonNode parsed = fields.parseObject(bytes("{\"future\":1e999}"));

        assertThat(parsed.path("future").decimalValue()).isEqualByComparingTo(new BigDecimal("1e999"));
    }

    @Test
    void classifiesJacksonNumberLengthLimitAsInvalidJson() {
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(8).build()).build();
        StrictEventFields fields = new StrictEventFields(new ObjectMapper(factory));

        assertThatThrownBy(() -> fields.parseObject(bytes("{\"future\":123456789}")))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class, exception -> {
                    assertThat(exception.reasonCode()).isEqualTo("INVALID_JSON");
                    assertThat(exception.getCause()).isInstanceOf(StreamConstraintsException.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"future\":1e2147483648}",
            "{\"future\":{\"nested\":[1e2147483648]}}"
    })
    @SuppressWarnings("unchecked")
    void writesSchemaDeadLetterBeforeAckWithoutDependencyRetry(String json) throws Exception {
        RealtimeStatusTransaction transaction = mock(RealtimeStatusTransaction.class);
        StatusStreamRecordHandler handler = new StatusStreamRecordHandler(
                new StatusStreamEventParser(mapper), transaction);
        RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
        List<String> outcomes = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        AtomicReference<JsonNode> deadLetter = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        when(commands.xadd(any(byte[].class), anyMap())).thenAnswer(invocation -> {
            Map<byte[], byte[]> body = invocation.getArgument(1);
            deadLetter.set(mapper.readTree(body.values().iterator().next()));
            outcomes.add("dead-letter");
            return "2-0";
        });
        doAnswer(invocation -> {
            outcomes.add("ack");
            return 1L;
        }).when(commands).xack(any(byte[].class), any(byte[].class), eq("1-0"));
        StreamRetryBackoff backoff = new StreamRetryBackoff(delay -> {
            delays.add(delay);
            if (delays.size() == 2) {
                running.set(false);
            }
        });
        RedisStreamRecordProcessor processor = new RedisStreamRecordProcessor(
                new StreamWorkerSpec("stream:status", "cg:realtime", "test", "stream:dead-letter"),
                handler, mapper, Clock.systemUTC());

        processor.process(new StreamMessage<>(bytes("stream:status"), "1-0",
                Map.of(bytes("payload"), bytes(json))), commands, running::get, backoff);

        assertThat(delays).isEmpty();
        assertThat(outcomes).containsExactly("dead-letter", "ack");
        assertThat(deadLetter.get().path("reasonCode").asText()).isEqualTo("INVALID_NUMERIC_VALUE");
        assertThat(deadLetter.get().path("attemptCount").asInt()).isOne();
        assertThat(deadLetter.get().path("payload").asText()).doesNotContain("2147483648");
        verifyNoInteractions(transaction);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsActualDependencyFailurePendingBeyondInvariantLimit() {
        RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
        List<Long> delays = new ArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        StreamRetryBackoff backoff = new StreamRetryBackoff(delay -> {
            delays.add(delay);
            if (delays.size() == 7) {
                running.set(false);
            }
        });
        RedisStreamRecordProcessor processor = new RedisStreamRecordProcessor(
                new StreamWorkerSpec("stream:status", "cg:realtime", "test", "stream:dead-letter"),
                record -> { throw new IllegalStateException("database unavailable"); }, mapper, Clock.systemUTC());

        processor.process(new StreamMessage<>(bytes("stream:status"), "1-0",
                Map.of(bytes("payload"), bytes("{}"))), commands, running::get, backoff);

        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L);
        verifyNoInteractions(commands);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}