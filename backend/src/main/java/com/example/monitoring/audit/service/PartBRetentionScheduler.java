package com.example.monitoring.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.part-b.retention-cleanup-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PartBRetentionScheduler {
    private final PartBRetentionService retentionService;

    @Scheduled(cron = "${app.part-b.retention-cleanup-cron:0 15 3 * * *}", zone = "UTC")
    public void purge() {
        try {
            PartBRetentionService.CleanupResult result = retentionService.purge(Instant.now());
            log.info("Part B retention cleanup completed. audits={}, accesses={}, usedTokens={}, sessions={}",
                    result.audits(), result.accesses(), result.usedTokens(), result.sessions());
        } catch (RuntimeException exception) {
            log.error("Part B retention cleanup failed", exception);
        }
    }
}
