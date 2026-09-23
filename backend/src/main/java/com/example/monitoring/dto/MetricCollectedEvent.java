package com.example.monitoring.dto;

import com.example.monitoring.domain.CollectionStatus;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricCollectedEvent {
    private Long databaseConfigId;
    private String databaseName;
    private String host;
    private Integer port;
    private LocalDateTime timestamp;
    private LocalDateTime collectionAttemptTime;
    
    // Nullable metric values (0 vs null distinction)
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
}
