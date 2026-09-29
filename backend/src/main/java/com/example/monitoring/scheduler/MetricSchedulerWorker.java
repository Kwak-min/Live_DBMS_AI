package com.example.monitoring.scheduler;

import com.example.monitoring.collector.DbMetricsCollector;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.metric.MetricCollectionRecorder;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 5초 주기 수집 스케줄러. 수집과 기록만 담당하며 위험도 판단·사건·차단은 하지 않는다(C 소유).
 * 이전 수집이 아직 실행 중인 대상은 이번 tick을 건너뛰어 같은 대상을 동시에 두 번 수집하지 않는다.
 * 대상끼리 서로 기다리지 않으므로 느린 대상이 다른 대상의 주기를 늦추지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.collector.enabled", havingValue = "true", matchIfMissing = true)
public class MetricSchedulerWorker {

    private final TargetProvider targetProvider;
    private final DbMetricsCollector dbMetricsCollector;
    private final MetricCollectionRecorder metricCollectionRecorder;

    private final ExecutorService collectionExecutor = Executors.newFixedThreadPool(10);
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedRateString = "${app.collector.fixed-rate-ms:5000}")
    public void executeCollectionCycle() {
        List<CollectorTarget> targets;
        try {
            targets = targetProvider.listEnabled();
        } catch (Exception e) {
            log.error("Failed to load collection targets; skipping this cycle.", e);
            return;
        }
        for (CollectorTarget target : targets) {
            submit(target);
        }
    }

    void submit(CollectorTarget target) {
        if (!inFlight.add(target.id())) {
            log.debug("Previous collection still running; skipping tick. databaseConfigId={}", target.id());
            return;
        }
        try {
            collectionExecutor.execute(() -> {
                try {
                    collectAndRecord(target);
                } finally {
                    inFlight.remove(target.id());
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(target.id());
            log.warn("Collection executor rejected target. databaseConfigId={}", target.id());
        }
    }

    void collectAndRecord(CollectorTarget target) {
        try {
            MetricData metric = dbMetricsCollector.collectMetrics(target);
            if (metricCollectionRecorder.record(target, metric).isEmpty()) {
                log.info("Discarded collection result: target changed during collection. databaseConfigId={}, "
                        + "collectedVersion={}", target.id(), target.configVersion());
            }
        } catch (Exception e) {
            // PostgreSQL 장애 등으로 저장하지 못하면 이벤트도 발행하지 않는다.
            log.error("Failed to collect or record metrics. databaseConfigId={}", target.id(), e);
        }
    }

    boolean isInFlight(long databaseConfigId) {
        return inFlight.contains(databaseConfigId);
    }

    @PreDestroy
    void shutdown() {
        collectionExecutor.shutdownNow();
    }
}
