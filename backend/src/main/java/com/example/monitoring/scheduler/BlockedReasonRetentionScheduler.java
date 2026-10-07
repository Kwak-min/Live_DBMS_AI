package com.example.monitoring.scheduler;

import com.example.monitoring.repository.BlockedReasonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 기존 차단 이력(blocked_reasons)을 발생 후 180일 보관 뒤 삭제한다 (docs/integration-operations.md 5절).
 * blocked_at은 V7부터 timestamptz다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.legacy.blocked-reasons-cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class BlockedReasonRetentionScheduler {

    static final int RETENTION_DAYS = 180;

    private final BlockedReasonRepository blockedReasonRepository;

    private Clock clock = Clock.systemUTC();

    @Scheduled(cron = "${app.legacy.blocked-reasons-cleanup-cron:0 10 3 * * *}", zone = "UTC")
    public void purgeExpired() {
        try {
            Instant cutoff = clock.instant().minus(Duration.ofDays(RETENTION_DAYS));
            int deleted = blockedReasonRepository.deleteBlockedBefore(cutoff);
            if (deleted > 0) {
                log.info("Purged {} legacy blocked reason(s) older than {}.", deleted, cutoff);
            }
        } catch (Exception e) {
            log.error("Unhandled exception during blocked reason retention cleanup.", e);
        }
    }
}
