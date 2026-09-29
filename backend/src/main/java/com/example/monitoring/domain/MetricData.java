package com.example.monitoring.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 수집 스냅샷 (A 소유). 필드 의미는 docs/events.md 지표 사전을 따른다.
 * nullable 지표가 null이면 그 이유를 unavailableMetrics에 기록하고, 실제 0은 그대로 저장한다.
 */
@Entity
@Table(name = "metric_data", indexes = {
    @Index(name = "idx_metric_data_target_time", columnList = "database_config_id, timestamp DESC, id DESC")
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

    /** 이 수집에 사용한 설정 버전 */
    @Column(nullable = false)
    private Long configVersion;

    /** 수집 시작 시각 */
    @Column(nullable = false)
    private Instant timestamp;

    /** 실제 시작 시각. MVP에서는 timestamp와 같다. */
    @Column(nullable = false)
    private Instant collectionAttemptTime;

    /** 같은 configVersion에서 이 스냅샷까지 마지막 SUCCESS의 관측 시각 */
    private Instant lastSuccessAt;

    private Double cpuUsage;
    private Double memoryUsage;
    private Long activeConnections;    // Threads_connected
    private Long maxConnections;       // max_connections
    private Double qps;                // Queries 증가량 / 실제 경과 초
    private Long slowQueries;          // Slow_queries 누적 카운터
    private Long slowQueriesDelta;     // 같은 구간의 Slow_queries 증가량
    private Double slowQueriesPerSecond;
    private Double metricWindowSeconds;
    private Long threadsRunning;       // Threads_running
    private Long storageBytes;         // information_schema.TABLES data_length + index_length 합
    private Long responseTimeMs;       // JDBC 연결 성립(또는 실패)까지 시간

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CollectionStatus collectionStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private MetricErrorCode errorCode;

    @Column(length = 500)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    @Builder.Default
    private Map<String, MetricUnavailableReason> unavailableMetrics = new LinkedHashMap<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
