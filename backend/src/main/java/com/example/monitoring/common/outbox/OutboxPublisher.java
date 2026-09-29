package com.example.monitoring.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * outbox 발행기. 1초마다 최대 100건을 생성 순서대로 Redis Stream에 발행한다.
 * 같은 orderingKey는 앞 이벤트가 발행될 때까지 뒤 이벤트를 보내지 않는다.
 * Redis 실패는 성공으로 처리하지 않고 1/2/4/8/16/30초 backoff로 무기한 재시도한다.
 * 같은 eventId가 여러 번 전달될 수 있으며 소비자가 eventId로 중복을 제거한다. MVP는 단일 인스턴스로 실행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    static final int BATCH_SIZE = 100;
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate stringRedisTemplate;

    private Clock clock = Clock.systemUTC();

    @Scheduled(fixedDelayString = "${app.outbox.publish-interval-ms:1000}")
    public void publishPending() {
        try {
            publishBatch();
        } catch (Exception e) {
            log.error("Unhandled exception during outbox publish cycle.", e);
        }
    }

    /** @return 이번 주기에 발행한 이벤트 수 */
    int publishBatch() {
        Instant now = clock.instant();
        List<OutboxEvent> events = outboxEventRepository.findUnpublished(PageRequest.of(0, BATCH_SIZE));
        Set<String> blockedKeys = new HashSet<>();
        int published = 0;

        for (OutboxEvent event : events) {
            String orderingKey = event.getOrderingKey();
            if (orderingKey != null && blockedKeys.contains(orderingKey)) {
                continue;
            }
            if (event.getNextAttemptAt().isAfter(now)) {
                if (orderingKey != null) {
                    blockedKeys.add(orderingKey);
                }
                continue;
            }

            try {
                publish(event);
            } catch (RuntimeException e) {
                recordFailure(event, now, e);
                // Redis 장애는 보통 전체 장애이므로 이번 주기를 멈추고 다음 주기에 재시도한다.
                break;
            }
            event.setPublishedAt(clock.instant());
            event.setLastError(null);
            outboxEventRepository.save(event);
            published++;
        }
        return published;
    }

    private void publish(OutboxEvent event) {
        RecordId recordId = stringRedisTemplate.opsForStream().add(
                StreamRecords.string(Map.of("payload", event.getPayload())).withStreamKey(event.getStreamKey()));
        if (recordId == null) {
            throw new IllegalStateException("Redis XADD returned no record id");
        }
        log.debug("Published outbox event. eventId={}, type={}, stream={}, recordId={}",
                event.getEventId(), event.getEventType(), event.getStreamKey(), recordId);
    }

    private void recordFailure(OutboxEvent event, Instant now, RuntimeException e) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempts)));
        event.setLastError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        outboxEventRepository.save(event);
        log.warn("Outbox publish failed; will retry. eventId={}, type={}, attempts={}, nextAttemptAt={}",
                event.getEventId(), event.getEventType(), attempts, event.getNextAttemptAt(), e);
    }

    static long backoffSeconds(int attempts) {
        int index = Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1;
        return BACKOFF_SECONDS[index];
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
