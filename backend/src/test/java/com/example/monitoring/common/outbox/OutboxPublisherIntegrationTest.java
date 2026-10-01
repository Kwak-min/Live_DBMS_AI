package com.example.monitoring.common.outbox;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 검수 시나리오 T13: PostgreSQL 저장 뒤 Redis 실패와 publisher 재시작.
 * 실패한 발행은 outbox에 남고, 새 publisher 인스턴스(재시작)가 같은 eventId·payload로 다시 발행한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({EmbeddedPostgresSupport.Config.class, OutboxWriter.class, UtcInstantJacksonConfig.class})
class OutboxPublisherIntegrationTest {

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    record SamplePayload(long metricId, long databaseConfigId) {
    }

    @Test
    @DisplayName("Redis failure keeps the event; a restarted publisher re-sends the same eventId and metricId")
    void restartedPublisherResendsSameEvent() throws Exception {
        jdbcTemplate.update("DELETE FROM event_outbox WHERE published_at IS NULL");
        UUID eventId = UUID.randomUUID();
        outboxWriter.append(eventId, OutboxEventType.METRIC_COLLECTED, "database:7", new SamplePayload(901L, 7L));
        outboxEventRepository.flush();
        Instant failedAt = outboxEventRepository.findById(eventId).orElseThrow().getCreatedAt();

        StreamOperations<String, Object, Object> failing = streams();
        given(failing.add(any(StringRecord.class))).willThrow(new RedisConnectionFailureException("redis down"));
        assertThat(publisher(failing, failedAt).publishBatch()).isZero();
        reload();

        OutboxEvent kept = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(kept.getPublishedAt()).isNull();
        assertThat(kept.getAttempts()).isEqualTo(1);
        assertThat(kept.getLastError()).contains("redis down");
        assertThat(kept.getNextAttemptAt()).isEqualTo(failedAt.plusSeconds(1));

        // 재시작: 메모리 상태 없이 새 인스턴스가 DB의 미발행 이벤트만 보고 다시 보낸다.
        StreamOperations<String, Object, Object> recovered = streams();
        given(recovered.add(any(StringRecord.class))).willReturn(RecordId.of("1-0"));
        assertThat(publisher(recovered, failedAt.plusSeconds(1)).publishBatch()).isEqualTo(1);
        reload();

        ArgumentCaptor<StringRecord> record = ArgumentCaptor.forClass(StringRecord.class);
        verify(recovered).add(record.capture());
        var published = objectMapper.readTree(record.getValue().getValue().get("payload"));
        assertThat(record.getValue().getStream()).isEqualTo("stream:metrics");
        assertThat(published.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(published.get("metricId").asLong()).isEqualTo(901L);
        assertThat(outboxEventRepository.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();
    }

    private OutboxPublisher publisher(StreamOperations<String, Object, Object> streams, Instant now) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.opsForStream()).willReturn(streams);
        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, redis);
        ReflectionTestUtils.setField(publisher, "clock", Clock.fixed(now, ZoneOffset.UTC));
        return publisher;
    }

    @SuppressWarnings("unchecked")
    private static StreamOperations<String, Object, Object> streams() {
        return mock(StreamOperations.class);
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }
}
