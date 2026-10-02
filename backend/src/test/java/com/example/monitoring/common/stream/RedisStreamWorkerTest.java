package com.example.monitoring.common.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisStreamWorkerTest {

    @Test
    void verifiesPrerequisiteBeforeResolvingRedisOrStartingAThread() {
        LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
        StreamRecordHandler handler = new StreamRecordHandler() {
            @Override
            public void verifyPrerequisite() {
                throw new IllegalStateException("missing monitoring_states");
            }

            @Override
            public void handle(StreamRecord record) {
            }
        };
        RedisStreamWorker worker = new RedisStreamWorker(
                connectionFactory,
                spec(),
                handler,
                new ObjectMapper());

        assertThatThrownBy(worker::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing monitoring_states");
        assertThat(worker.isRunning()).isFalse();
        verify(connectionFactory, never()).getRequiredNativeClient();
    }

    @Test
    void dependencyBackoffUsesTheRequiredCappedSequence() {
        List<Long> delays = new java.util.ArrayList<>();
        StreamRetryBackoff backoff = new StreamRetryBackoff(delays::add);

        for (int attempt = 0; attempt < 8; attempt++) {
            backoff.pause();
        }

        assertThat(delays).containsExactly(
                1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L);
        backoff.reset();
        backoff.pause();
        assertThat(delays.get(delays.size() - 1)).isEqualTo(1_000L);
    }

    private StreamWorkerSpec spec() {
        return new StreamWorkerSpec(
                "stream:statuses",
                "cg:realtime",
                "realtime-status-consumer",
                "stream:dead-letter");
    }
}
