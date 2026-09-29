package com.example.monitoring.common.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxEventRepository, stringRedisTemplate);
        ReflectionTestUtils.setField(publisher, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        given(stringRedisTemplate.opsForStream()).willReturn(streamOperations);
    }

    @Test
    @DisplayName("Publishes due events in order with a single payload field and marks them published")
    void publishesDueEventsInOrder() {
        OutboxEvent first = event("database:1", NOW, "{\"n\":1}");
        OutboxEvent second = event("database:1", NOW, "{\"n\":2}");
        given(outboxEventRepository.findUnpublished(any(Pageable.class))).willReturn(List.of(first, second));
        given(streamOperations.add(any(StringRecord.class))).willReturn(RecordId.of("1-0"), RecordId.of("2-0"));

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(2);
        ArgumentCaptor<StringRecord> records = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations, times(2)).add(records.capture());
        assertThat(records.getAllValues()).extracting(StringRecord::getStream).containsOnly("stream:metrics");
        assertThat(records.getAllValues().get(0).getValue()).containsOnlyKeys("payload").containsEntry("payload", "{\"n\":1}");
        assertThat(records.getAllValues().get(1).getValue()).containsEntry("payload", "{\"n\":2}");
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Redis failure is not treated as success: attempts and backoff are recorded and the cycle stops")
    void failureRecordsBackoffAndStops() {
        OutboxEvent first = event("database:1", NOW, "{}");
        first.setAttempts(2);
        OutboxEvent other = event("database:2", NOW, "{}");
        given(outboxEventRepository.findUnpublished(any(Pageable.class))).willReturn(List.of(first, other));
        given(streamOperations.add(any(StringRecord.class))).willThrow(new RedisConnectionFailureException("down"));

        int published = publisher.publishBatch();

        assertThat(published).isZero();
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(3);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(first.getLastError()).contains("down");
        verify(outboxEventRepository).save(first);
        verify(streamOperations, times(1)).add(any(StringRecord.class));
        verify(outboxEventRepository, never()).save(other);
    }

    @Test
    @DisplayName("An event waiting for retry blocks later events with the same ordering key only")
    void waitingEventBlocksSameKey() {
        OutboxEvent waiting = event("database:1", NOW.plusSeconds(5), "{\"n\":1}");
        OutboxEvent blocked = event("database:1", NOW, "{\"n\":2}");
        OutboxEvent otherKey = event("database:2", NOW, "{\"n\":3}");
        OutboxEvent unordered = event(null, NOW, "{\"n\":4}");
        given(outboxEventRepository.findUnpublished(any(Pageable.class)))
                .willReturn(List.of(waiting, blocked, otherKey, unordered));
        given(streamOperations.add(any(StringRecord.class))).willReturn(RecordId.of("1-0"));

        int published = publisher.publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(waiting.getPublishedAt()).isNull();
        assertThat(blocked.getPublishedAt()).isNull();
        assertThat(otherKey.getPublishedAt()).isEqualTo(NOW);
        assertThat(unordered.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Backoff follows 1/2/4/8/16/30 seconds and stays at 30 without giving up")
    void backoffSequence() {
        assertThat(List.of(1, 2, 3, 4, 5, 6, 7, 100).stream().map(OutboxPublisher::backoffSeconds).toList())
                .containsExactly(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L);
    }

    private static OutboxEvent event(String orderingKey, Instant nextAttemptAt, String payload) {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("MetricCollectedEvent")
                .streamKey("stream:metrics")
                .orderingKey(orderingKey)
                .payload(payload)
                .createdAt(NOW)
                .attempts(0)
                .nextAttemptAt(nextAttemptAt)
                .build();
    }
}
