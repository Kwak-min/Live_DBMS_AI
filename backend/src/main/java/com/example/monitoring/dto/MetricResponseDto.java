package com.example.monitoring.dto;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.domain.MetricUnavailableReason;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** REST Metric (docs/api.md 4절, 지표 사전 docs/events.md 1절). id는 MetricCollectedEvent의 metricId와 같다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(name = "Metric", description = "Raw metric snapshot. Every field is always present; nullable fields are null "
        + "when not measured and the reason is in unavailableMetrics. All numbers are finite and non-negative; "
        + "a measured 0 is 0, never null.")
public class MetricResponseDto {
    @Schema(requiredMode = REQUIRED, description = "Snapshot Id; equals metricId of MetricCollectedEvent")
    private Long id;
    @Schema(requiredMode = REQUIRED, description = "Target database Id")
    private Long databaseConfigId;
    @Schema(requiredMode = REQUIRED, description = "Target configVersion used for this collection")
    private Long configVersion;
    @Schema(requiredMode = REQUIRED, description = "Collection start, UTC Time")
    private Instant timestamp;
    @Schema(requiredMode = REQUIRED, description = "Actual start, UTC Time; equal to timestamp in MVP")
    private Instant collectionAttemptTime;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Last SUCCESS observation time up to this snapshot within the same configVersion, UTC Time")
    private Instant lastSuccessAt;

    @Schema(requiredMode = REQUIRED, nullable = true, description = "Percent 0~100; no source yet, always null (UNSUPPORTED)")
    private Double cpuUsage;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Percent 0~100; no source yet, always null (UNSUPPORTED)")
    private Double memoryUsage;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Count; Threads_connected (not active queries)")
    private Long activeConnections;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Count; max_connections")
    private Long maxConnections;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Queries per second over metricWindowSeconds; null on WARMUP/COUNTER_RESET")
    private Double qps;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Count; cumulative Slow_queries counter, may decrease after a reset or restart")
    private Long slowQueries;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Count; Slow_queries increase in the window")
    private Long slowQueriesDelta;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Per second; slowQueriesDelta / metricWindowSeconds")
    private Double slowQueriesPerSecond;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Seconds; actual interval between the two observations used by derived metrics, > 0")
    private Double metricWindowSeconds;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Count; Threads_running")
    private Long threadsRunning;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Bytes; data_length + index_length of all information_schema.TABLES visible to the account")
    private Long storageBytes;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Milliseconds until the JDBC connection was established, or until it failed")
    private Long responseTimeMs;

    @Schema(requiredMode = REQUIRED, description = "SUCCESS: all required sources read; PARTIAL_FAILURE: some "
            + "required sources failed (successful fields kept); CONNECTION_FAILED: could not connect")
    private CollectionStatus collectionStatus;
    @Schema(requiredMode = REQUIRED, nullable = true, description = "Failure cause; null on SUCCESS")
    private MetricErrorCode errorCode;
    @Schema(requiredMode = REQUIRED, nullable = true,
            description = "Safe short description; never contains SQL, credentials or driver messages")
    private String errorMessage;
    @Schema(requiredMode = REQUIRED, description = "Metric field name -> reason it is null. "
            + "A measured 0 is never listed. e.g. {\"cpuUsage\":\"UNSUPPORTED\",\"qps\":\"WARMUP\"}")
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
