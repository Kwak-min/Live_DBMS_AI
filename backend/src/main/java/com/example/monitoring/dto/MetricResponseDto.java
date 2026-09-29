package com.example.monitoring.dto;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.domain.MetricUnavailableReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/** REST Metric (docs/api.md 4절). id는 MetricCollectedEvent의 metricId와 같다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricResponseDto {
    private Long id;
    private Long databaseConfigId;
    private Long configVersion;
    private Instant timestamp;
    private Instant collectionAttemptTime;
    private Instant lastSuccessAt;

    private Double cpuUsage;
    private Double memoryUsage;
    private Long activeConnections;
    private Long maxConnections;
    private Double qps;
    private Long slowQueries;
    private Long slowQueriesDelta;
    private Double slowQueriesPerSecond;
    private Double metricWindowSeconds;
    private Long threadsRunning;
    private Long storageBytes;
    private Long responseTimeMs;

    private CollectionStatus collectionStatus;
    private MetricErrorCode errorCode;
    private String errorMessage;
    private Map<String, MetricUnavailableReason> unavailableMetrics;

    public static MetricResponseDto fromEntity(MetricData entity) {
        return MetricResponseDto.builder()
                .id(entity.getId())
                .databaseConfigId(entity.getDatabaseConfig() != null ? entity.getDatabaseConfig().getId() : null)
                .configVersion(entity.getConfigVersion())
                .timestamp(entity.getTimestamp())
                .collectionAttemptTime(entity.getCollectionAttemptTime())
                .lastSuccessAt(entity.getLastSuccessAt())
                .cpuUsage(entity.getCpuUsage())
                .memoryUsage(entity.getMemoryUsage())
                .activeConnections(entity.getActiveConnections())
                .maxConnections(entity.getMaxConnections())
                .qps(entity.getQps())
                .slowQueries(entity.getSlowQueries())
                .slowQueriesDelta(entity.getSlowQueriesDelta())
                .slowQueriesPerSecond(entity.getSlowQueriesPerSecond())
                .metricWindowSeconds(entity.getMetricWindowSeconds())
                .threadsRunning(entity.getThreadsRunning())
                .storageBytes(entity.getStorageBytes())
                .responseTimeMs(entity.getResponseTimeMs())
                .collectionStatus(entity.getCollectionStatus())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .unavailableMetrics(entity.getUnavailableMetrics())
                .build();
    }
}
