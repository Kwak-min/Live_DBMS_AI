package com.example.monitoring.common.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.redis.stream-retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class RedisStreamRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamRetentionScheduler.class);
    private static final RedisScript<Long> TRIM = RedisScript.of(
            new ClassPathResource("redis/safe-stream-trim.lua"), Long.class);
    private static final List<String> METRIC_GROUPS = List.of("cg:risk", "cg:realtime");
    private static final List<String> HEARTBEAT_GROUPS = List.of("cg:risk");
    private static final List<String> STATUS_GROUPS = List.of("cg:realtime");
    private static final List<String> INCIDENT_GROUPS = List.of("cg:realtime", "cg:notification");
    private static final int BATCH_SIZE = 1_000;
    private static final long MIN_MAX_AGE_HOURS = 24;

    private final StringRedisTemplate redis;
    private final String metricStream;
    private final String heartbeatStream;
    private final String statusStream;
    private final String incidentStream;
    private final long maxAgeMs;

    public RedisStreamRetentionScheduler(
            StringRedisTemplate redis,
            @Value("${app.redis.stream-key:stream:metrics}") String metricStream,
            @Value("${app.redis.heartbeat-stream-key:stream:collector-heartbeats}") String heartbeatStream,
            @Value("${app.redis.status-stream-key:stream:statuses}") String statusStream,
            @Value("${app.redis.incident-stream-key:stream:incidents}") String incidentStream,
            @Value("${app.redis.stream-retention.max-age-hours:168}") long maxAgeHours
    ) {
        if (maxAgeHours != 0 && maxAgeHours < MIN_MAX_AGE_HOURS) {
            throw new IllegalArgumentException(
                    "app.redis.stream-retention.max-age-hours must be 0 (disabled) or at least 24");
        }
        this.redis = redis;
        this.metricStream = metricStream;
        this.heartbeatStream = heartbeatStream;
        this.statusStream = statusStream;
        this.incidentStream = incidentStream;
        this.maxAgeMs = maxAgeHours * 3_600_000L;
    }

    @Scheduled(fixedDelayString = "${app.redis.stream-retention.interval-ms:60000}",
            initialDelayString = "${app.redis.stream-retention.interval-ms:60000}")
    public void trimStreams() {
        trimSafely(metricStream, METRIC_GROUPS);
        trimSafely(heartbeatStream, HEARTBEAT_GROUPS);
        trimSafely(statusStream, STATUS_GROUPS);
        trimSafely(incidentStream, INCIDENT_GROUPS);
    }

    long trim(String stream, List<String> requiredGroups) {
        List<String> arguments = new ArrayList<>();
        arguments.add(Integer.toString(BATCH_SIZE));
        arguments.add(Long.toString(maxAgeMs));
        arguments.addAll(requiredGroups);
        Long removed = redis.execute(TRIM, List.of(stream), arguments.toArray());
        if (removed == null) {
            throw new IllegalStateException("Redis stream trim returned no result");
        }
        return removed;
    }

    private void trimSafely(String stream, List<String> requiredGroups) {
        try {
            long removed = trim(stream, requiredGroups);
            if (removed > 0) {
                log.info("Redis stream retention completed; stream={}, removed={}", stream, removed);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis stream retention deferred; stream={}, cause={}",
                    stream, exception.getClass().getSimpleName());
        }
    }
}
