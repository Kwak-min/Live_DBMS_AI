package com.example.monitoring.scheduler;

import com.example.monitoring.collector.DbMetricsCollector;
import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.RiskSeverity;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.IncidentCreatedEvent;
import com.example.monitoring.dto.MetricCollectedEvent;
import com.example.monitoring.infrastructure.redis.IncidentPublisher;
import com.example.monitoring.infrastructure.redis.RedisStreamPublisher;
import com.example.monitoring.repository.DatabaseConfigRepository;
import com.example.monitoring.repository.MetricDataRepository;
import com.example.monitoring.service.ProjectIsolationService;
import com.example.monitoring.service.RiskAssessmentEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.collector.enabled", havingValue = "true", matchIfMissing = true)
public class MetricSchedulerWorker {

    private final DatabaseConfigRepository databaseConfigRepository;
    private final MetricDataRepository metricDataRepository;
    private final DbMetricsCollector dbMetricsCollector;
    private final RedisStreamPublisher redisStreamPublisher;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final IncidentPublisher incidentPublisher;
    private final ProjectIsolationService projectIsolationService;

    private final ExecutorService collectionExecutor = Executors.newFixedThreadPool(10);

    @Scheduled(fixedRateString = "${app.collector.fixed-rate-ms:5000}")
    public void executeCollectionCycle() {
        List<DatabaseConfig> targetDatabases = databaseConfigRepository.findByEnabledTrue();
        if (targetDatabases.isEmpty()) {
            log.trace("No active database configurations found for metric collection.");
            return;
        }

        log.debug("Starting metric collection cycle for {} target database(s).", targetDatabases.size());

        List<CompletableFuture<Void>> futures = targetDatabases.stream()
                .map(config -> CompletableFuture.runAsync(() -> processSingleTarget(config), collectionExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void processSingleTarget(DatabaseConfig config) {
        LocalDateTime attemptTime = LocalDateTime.now();
        try {
            // 1. 메트릭 수집
            MetricData metricData = dbMetricsCollector.collectMetrics(config);

            // 2. DatabaseConfig 상태 업데이트
            if (metricData.getCollectionStatus() == CollectionStatus.SUCCESS) {
                config.setStatus(TargetDbStatus.UP);
                config.setLastErrorMessage(null);
            } else {
                config.setStatus(TargetDbStatus.DOWN);
                config.setLastErrorMessage(metricData.getErrorMessage());
            }
            config.setLastCheckedAt(attemptTime);
            databaseConfigRepository.save(config);

            // 3. 메트릭 스냅샷 저장 (PostgreSQL)
            MetricData savedMetric = metricDataRepository.save(metricData);

            // 4. MetricCollectedEvent 빌드
            MetricCollectedEvent event = MetricCollectedEvent.builder()
                    .databaseConfigId(config.getId())
                    .databaseName(config.getName())
                    .host(config.getHost())
                    .port(config.getPort())
                    .timestamp(savedMetric.getTimestamp())
                    .collectionAttemptTime(attemptTime)
                    .cpuUsage(savedMetric.getCpuUsage())
                    .memoryUsage(savedMetric.getMemoryUsage())
                    .activeConnections(savedMetric.getActiveConnections())
                    .maxConnections(savedMetric.getMaxConnections())
                    .qps(savedMetric.getQps())
                    .slowQueries(savedMetric.getSlowQueries())
                    .threadsRunning(savedMetric.getThreadsRunning())
                    .storageBytes(savedMetric.getStorageBytes())
                    .responseTimeMs(savedMetric.getResponseTimeMs())
                    .collectionStatus(savedMetric.getCollectionStatus())
                    .errorMessage(savedMetric.getErrorMessage())
                    .build();

            // 5. MetricCollectedEvent → Redis Streams 발행
            redisStreamPublisher.publish(event);

            // 6. 위험도 평가
            List<IncidentCreatedEvent> incidents = riskAssessmentEngine.evaluateRisk(event);

            // 7. 인시던트별 처리: Redis 발행 + FATAL 자동 차단
            for (IncidentCreatedEvent incident : incidents) {
                incidentPublisher.publish(incident);

                if (incident.getSeverity() == RiskSeverity.FATAL) {
                    log.warn("[Scheduler] FATAL incident detected for DB [id={}, name={}]. Triggering auto-block.",
                            config.getId(), config.getName());
                    projectIsolationService.autoBlockOnFatalIncident(incident);
                }
            }

        } catch (Exception e) {
            log.error("Unhandled exception during collection execution for dbId: {}", config.getId(), e);
        }
    }
}

