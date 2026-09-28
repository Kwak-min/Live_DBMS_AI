package com.example.monitoring.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "metric_data", indexes = {
    @Index(name = "idx_metric_db_time", columnList = "database_config_id, timestamp DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "database_config_id", nullable = false)
    private DatabaseConfig databaseConfig;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    // Health Metrics - Nullable to distinguish failed metric (null) from zero (0.0 / 0)
    private Double cpuUsage;           // Percentage (0.0 ~ 100.0)
    private Double memoryUsage;        // Percentage (0.0 ~ 100.0)
    private Long activeConnections;    // Threads_connected
    private Long maxConnections;       // max_connections variable
    private Double qps;                // Queries Per Second
    private Long slowQueries;          // Slow_queries count
    private Long threadsRunning;       // Threads_running
    private Long storageBytes;         // Data + Index size in bytes
    private Long responseTimeMs;       // Ping/Query latency in milliseconds

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CollectionStatus collectionStatus;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        createdAt = LocalDateTime.now();
    }
}
