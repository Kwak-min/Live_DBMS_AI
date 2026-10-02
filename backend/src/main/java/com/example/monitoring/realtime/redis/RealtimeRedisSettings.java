package com.example.monitoring.realtime.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public record RealtimeRedisSettings(
        String sourceStream,
        String deadLetterStream,
        Duration reclaimMinIdle,
        Duration reclaimInterval
) {

    public RealtimeRedisSettings(
            @Value("${app.redis.stream-key:stream:metrics}") String sourceStream,
            @Value("${monitoring.realtime.dead-letter-stream:stream:dead-letter}") String deadLetterStream,
            @Value("${monitoring.realtime.reclaim-min-idle:60s}") Duration reclaimMinIdle,
            @Value("${monitoring.realtime.reclaim-interval:30s}") Duration reclaimInterval
    ) {
        if (sourceStream == null || sourceStream.isBlank()) {
            throw new IllegalArgumentException("Realtime source stream must not be blank");
        }
        if (deadLetterStream == null || deadLetterStream.isBlank()) {
            throw new IllegalArgumentException("Realtime dead-letter stream must not be blank");
        }
        if (reclaimMinIdle == null || reclaimMinIdle.isNegative()) {
            throw new IllegalArgumentException("Realtime reclaim min idle must not be negative");
        }
        if (reclaimInterval == null || reclaimInterval.isZero() || reclaimInterval.isNegative()) {
            throw new IllegalArgumentException("Realtime reclaim interval must be positive");
        }
        this.sourceStream = sourceStream;
        this.deadLetterStream = deadLetterStream;
        this.reclaimMinIdle = reclaimMinIdle;
        this.reclaimInterval = reclaimInterval;
    }
}
