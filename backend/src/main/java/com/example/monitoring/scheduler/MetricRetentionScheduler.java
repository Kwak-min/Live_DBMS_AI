package com.example.monitoring.scheduler;

import com.example.monitoring.service.MetricRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.metrics.retention-cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class MetricRetentionScheduler {

    private final MetricRetentionService metricRetentionService;

    @Scheduled(cron = "${app.metrics.retention-cleanup-cron:0 0 3 * * *}")
    public void purgeExpiredMetrics() {
        try {
            metricRetentionService.purgeExpiredMetrics();
        } catch (Exception e) {
            log.error("Unhandled exception during metric retention cleanup.", e);
        }
    }
}
