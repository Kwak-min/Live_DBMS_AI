package com.example.monitoring.ai.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 하루(windowStart 포함, windowEnd 제외) 동안의 metric_data·incidents 집계.
 * 수집 표본이 없거나 해당 지표가 한 번도 수집되지 않았으면 그 값은 null이다.
 */
public record DailyStats(
        Instant windowStart,
        Instant windowEnd,
        long sampleCount,
        long successCount,
        long partialFailureCount,
        long connectionFailedCount,
        Double availabilityPercent,
        Double avgActiveConnections,
        Long maxActiveConnections,
        Long maxConnections,
        Double avgConnectionUsagePercent,
        Double maxConnectionUsagePercent,
        Double avgQps,
        Double maxQps,
        Long slowQueriesTotal,
        Double maxSlowQueriesPerSecond,
        Double avgThreadsRunning,
        Long maxThreadsRunning,
        Double avgResponseTimeMs,
        Double p95ResponseTimeMs,
        Long storageBytesStart,
        Long storageBytesEnd,
        Map<String, Long> errorCounts,
        long incidentCount,
        List<IncidentSummary> incidents,
        List<HourlyStat> hourly
) {
}
