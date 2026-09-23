package com.example.monitoring.service;

import com.example.monitoring.repository.MetricDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricRetentionService {

    private final MetricDataRepository metricDataRepository;

    @Value("${app.metrics.retention-days:30}")
    private int retentionDays;

    /**
     * Deletes metric snapshots older than the configured retention period.
     * Records within the retention window are never touched.
     *
     * @return number of deleted rows
     */
    @Transactional
    public int purgeExpiredMetrics() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = metricDataRepository.deleteByTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} metric snapshot(s) older than {} (retention: {} days).", deleted, cutoff, retentionDays);
        } else {
            log.debug("No metric snapshots older than {} to purge (retention: {} days).", cutoff, retentionDays);
        }
        return deleted;
    }
}
