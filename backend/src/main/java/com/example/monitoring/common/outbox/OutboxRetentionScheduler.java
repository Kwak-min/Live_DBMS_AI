package com.example.monitoring.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 발행 완료 outbox와 processed_events를 31일 보관 후 삭제한다. 미발행 outbox는 기간과 무관하게 보존한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.retention-cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRetentionScheduler {

    static final Duration RETENTION = Duration.ofDays(31);

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventStore processedEventStore;

    private Clock clock = Clock.systemUTC();

    @Scheduled(cron = "${app.outbox.retention-cleanup-cron:0 30 3 * * *}", zone = "UTC")
    public void purgeExpired() {
        try {
            Instant cutoff = clock.instant().minus(RETENTION);
            int outbox = outboxEventRepository.deletePublishedBefore(cutoff);
            int processed = processedEventStore.deleteProcessedBefore(cutoff);
            log.info("Outbox retention cleanup finished. cutoff={}, outboxDeleted={}, processedDeleted={}",
                    cutoff, outbox, processed);
        } catch (Exception e) {
            log.error("Unhandled exception during outbox retention cleanup.", e);
        }
    }
}
