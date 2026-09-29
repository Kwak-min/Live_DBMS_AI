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

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 5초 주기 수집 스케줄러. 수집과 기록만 담당하며 위험도 판단·사건·차단은 하지 않는다(C 소유).
 * 이전 수집이 아직 실행 중인 대상은 이번 tick을 건너뛰어 같은 대상을 동시에 두 번 수집하지 않는다.
 * 대상끼리 서로 기다리지 않으므로 느린 대상이 다른 대상의 주기를 늦추지 않는다.
 * 주기 시작·완료 시각은 CollectorHeartbeatEvent로 알린다. 완료는 그 주기에 보낸 수집이 모두 끝난 시각이다.
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
    private Clock clock = Clock.systemUTC();

    private volatile Cycle currentCycle;
    private volatile Instant lastCycleCompletedAt;

    /** 한 tick에서 보낸 수집 묶음. 남은 작업이 0이 되면 완료 시각을 기록한다. */
    private final class Cycle {
        final Instant startedAt;
        final AtomicInteger remaining = new AtomicInteger(1); // 배분이 끝날 때까지 1을 잡아 둔다.
        volatile boolean completed;

        Cycle(Instant startedAt) {
            this.startedAt = startedAt;
        }

        void taskFinished() {
            if (remaining.decrementAndGet() == 0) {
                completed = true;
                lastCycleCompletedAt = now();
            }
        }
    }

    @Scheduled(fixedRateString = "${app.collector.fixed-rate-ms:5000}")
    public void executeCollectionCycle() {
        Cycle cycle = new Cycle(now());
        currentCycle = cycle;
        try {
            List<CollectorTarget> targets = targetProvider.listEnabled();
            for (CollectorTarget target : targets) {
                submit(target, cycle);
            }
        } catch (Exception e) {
            log.error("Failed to load collection targets; skipping this cycle.", e);
        } finally {
            cycle.taskFinished();
        }
    }

    private void submit(CollectorTarget target, Cycle cycle) {
        if (!inFlight.add(target.id())) {
            log.debug("Previous collection still running; skipping tick. databaseConfigId={}", target.id());
            return;
        }
        cycle.remaining.incrementAndGet();
        try {
            collectionExecutor.execute(() -> {
                try {
                    collectAndRecord(target);
                } finally {
                    inFlight.remove(target.id());
                    cycle.taskFinished();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(target.id());
            cycle.taskFinished();
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

    /** Heartbeat용 현재 주기 상태 */
    public CycleStatus cycleStatus() {
        Cycle cycle = currentCycle;
        return new CycleStatus(cycle == null ? null : cycle.startedAt, lastCycleCompletedAt,
                cycle != null && !cycle.completed);
    }

    public record CycleStatus(Instant lastCycleStartedAt, Instant lastCycleCompletedAt, boolean cycleInProgress) {
    }

    boolean isInFlight(long databaseConfigId) {
        return inFlight.contains(databaseConfigId);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    @PreDestroy
    void shutdown() {
        collectionExecutor.shutdownNow();
    }
}
