package com.example.monitoring.dto;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricResponseDto {
    private Long id;
    private Long databaseConfigId;
    private LocalDateTime timestamp;

    private Double cpuUsage;
    private Double memoryUsage;
    private Long activeConnections;
    private Long maxConnections;
    private Double qps;
    private Long slowQueries;
    private Long threadsRunning;
    private Long storageBytes;
    private Long responseTimeMs;

    private CollectionStatus collectionStatus;
    private String errorMessage;

    public static MetricResponseDto fromEntity(MetricData entity) {
        return MetricResponseDto.builder()
                .id(entity.getId())
                .databaseConfigId(entity.getDatabaseConfig() != null ? entity.getDatabaseConfig().getId() : null)
                .timestamp(entity.getTimestamp())
                .cpuUsage(entity.getCpuUsage())
                .memoryUsage(entity.getMemoryUsage())
                .activeConnections(entity.getActiveConnections())
                .maxConnections(entity.getMaxConnections())
                .qps(entity.getQps())
                .slowQueries(entity.getSlowQueries())
                .threadsRunning(entity.getThreadsRunning())
                .storageBytes(entity.getStorageBytes())
                .responseTimeMs(entity.getResponseTimeMs())
                .collectionStatus(entity.getCollectionStatus())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
