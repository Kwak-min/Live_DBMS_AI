package com.example.monitoring.retention;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "monitoring.retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PartCRetentionScheduler {

    private final PartCRetentionService service;
    private final Clock clock;

    public PartCRetentionScheduler(PartCRetentionService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${monitoring.retention.cron:0 45 3 * * *}", zone = "UTC")
    public void purgeExpired() {
        long started = System.nanoTime();
        try {
            PartCRetentionService.CleanupResult result = service.purge(clock.instant());
            log.info(
                    "Part C retention cleanup completed. deliveries={}, incidents={}, batches={}, durationMs={}",
                    result.deliveries(), result.incidents(), result.batches(), elapsedMillis(started));
        } catch (RuntimeException exception) {
            log.error(
                    "Part C retention cleanup failed. durationMs={}, cause={}",
                    elapsedMillis(started), exception.getClass().getSimpleName());
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
