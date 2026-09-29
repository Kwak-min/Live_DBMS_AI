package com.example.monitoring.scheduler;

import com.example.monitoring.common.outbox.EventJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * CollectorHeartbeatEvent 발행 (docs/events.md). 수집 루프와 별도 스케줄러에서 10초마다 Redis로 직접 발행한다.
 * 과거 생존 신호를 재생하지 않도록 outbox를 쓰지 않으며, Redis 장애 시 이번 신호는 버린다.
 * C는 생성 후 30초가 지난 신호를 현재 생존으로 인정하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.collector.enabled", havingValue = "true", matchIfMissing = true)
public class CollectorHeartbeatPublisher {

    static final String EVENT_TYPE = "CollectorHeartbeatEvent";

    private final MetricSchedulerWorker metricSchedulerWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String collectorId = "collector-" + UUID.randomUUID();
    private Clock clock = Clock.systemUTC();

    @Value("${app.redis.heartbeat-stream-key:stream:collector-heartbeats}")
    private String streamKey;

    public CollectorHeartbeatPublisher(MetricSchedulerWorker metricSchedulerWorker,
                                       StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.metricSchedulerWorker = metricSchedulerWorker;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    record HeartbeatPayload(String collectorId, Instant timestamp, Instant lastCycleStartedAt,
                            Instant lastCycleCompletedAt, boolean cycleInProgress) {
    }

    @Scheduled(fixedRateString = "${app.collector.heartbeat-interval-ms:10000}")
    public void publishHeartbeat() {
        try {
            stringRedisTemplate.opsForStream().add(StreamRecords.string(Map.of("payload", buildEvent()))
                    .withStreamKey(streamKey));
        } catch (Exception e) {
            log.warn("Failed to publish collector heartbeat. collectorId={}, stream={}, exceptionType={}",
                    collectorId, streamKey, e.getClass().getSimpleName());
        }
    }

    String buildEvent() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        MetricSchedulerWorker.CycleStatus cycle = metricSchedulerWorker.cycleStatus();
        HeartbeatPayload payload = new HeartbeatPayload(collectorId, now, cycle.lastCycleStartedAt(),
                cycle.lastCycleCompletedAt(), cycle.cycleInProgress());
        return EventJson.build(objectMapper, UUID.randomUUID(), EVENT_TYPE, now, payload);
    }
}
