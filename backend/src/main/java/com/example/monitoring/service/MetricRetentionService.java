package com.example.monitoring.service;

import com.example.monitoring.repository.MetricDataRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 메트릭 보관 기간(기본 30일, 1~365) 정리. 긴 잠금을 피하려고 작은 배치마다 별도 트랜잭션으로 삭제한다.
 * 사건의 근거 값은 incidents에 스냅샷으로 보존되므로(C) 메트릭 삭제가 사건을 지우지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricRetentionService {

    static final int BATCH_SIZE = 1000;

    private final MetricDataRepository metricDataRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.metrics.retention-days:30}")
    private int retentionDays;

    private Clock clock = Clock.systemUTC();

    @PostConstruct
    void validateRetentionDays() {
        if (retentionDays < 1 || retentionDays > 365) {
            throw new IllegalStateException("app.metrics.retention-days must be 1~365 but was " + retentionDays);
        }
    }

    /** @return 삭제한 스냅샷 수 */
    public int purgeExpiredMetrics() {
        Instant cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch(cutoff);
            total += deleted;
        } while (deleted == BATCH_SIZE);

        if (total > 0) {
            log.info("Purged {} metric snapshot(s) older than {} (retention: {} days).", total, cutoff, retentionDays);
        } else {
            log.debug("No metric snapshots older than {} to purge (retention: {} days).", cutoff, retentionDays);
        }
        return total;
    }

    private int deleteBatch(Instant cutoff) {
        Integer deleted = transactionTemplate.execute(status -> {
            List<Long> ids = metricDataRepository.findIdsOlderThan(cutoff, PageRequest.of(0, BATCH_SIZE));
            if (!ids.isEmpty()) {
                metricDataRepository.deleteAllByIdInBatch(ids);
            }
            return ids.size();
        });
        return deleted == null ? 0 : deleted;
    }
}
