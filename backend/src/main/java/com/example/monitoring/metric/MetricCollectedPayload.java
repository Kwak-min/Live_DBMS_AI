package com.example.monitoring.metric;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.domain.MetricUnavailableReason;

import java.time.Instant;
import java.util.Map;

/**
 * MetricCollectedEvent 본문 (docs/events.md). REST Metric 전체 필드에서 id를 metricId로 바꾸고 표시명을 추가한다.
 * host/port/계정은 싣지 않는다. 공통 필드(schemaVersion, eventId, eventType, publishedAt)는 OutboxWriter가 붙인다.
 */
public record MetricCollectedPayload(
        long metricId,
        long databaseConfigId,
        long configVersion,
        String databaseName,
        Instant timestamp,
        Instant collectionAttemptTime,
        Instant lastSuccessAt,
        Double cpuUsage,
        Double memoryUsage,
        Long activeConnections,
        Long maxConnections,
        Double qps,
        Long slowQueries,
        Long slowQueriesDelta,
        Double slowQueriesPerSecond,
        Double metricWindowSeconds,
        Long threadsRunning,
        Long storageBytes,
        Long responseTimeMs,
        CollectionStatus collectionStatus,
        MetricErrorCode errorCode,
        String errorMessage,
        Map<String, MetricUnavailableReason> unavailableMetrics
) {
    public static MetricCollectedPayload from(MetricData metric, long databaseConfigId, String databaseName) {
        return new MetricCollectedPayload(
                metric.getId(), databaseConfigId, metric.getConfigVersion(), databaseName,
                metric.getTimestamp(), metric.getCollectionAttemptTime(), metric.getLastSuccessAt(),
                metric.getCpuUsage(), metric.getMemoryUsage(), metric.getActiveConnections(),
                metric.getMaxConnections(), metric.getQps(), metric.getSlowQueries(), metric.getSlowQueriesDelta(),
                metric.getSlowQueriesPerSecond(), metric.getMetricWindowSeconds(), metric.getThreadsRunning(),
                metric.getStorageBytes(), metric.getResponseTimeMs(), metric.getCollectionStatus(),
                metric.getErrorCode(), metric.getErrorMessage(), metric.getUnavailableMetrics());
    }
}
