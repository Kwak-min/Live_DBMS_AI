package com.example.monitoring.realtime.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MetricCollectedPayloadV1(
        int schemaVersion,
        UUID eventId,
        String eventType,
        Instant publishedAt,
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
        Map<String, UnavailableReason> unavailableMetrics
) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "MetricCollectedEvent";

    public MetricCollectedPayloadV1 {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(databaseName, "databaseName");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(collectionAttemptTime, "collectionAttemptTime");
        Objects.requireNonNull(collectionStatus, "collectionStatus");
        unavailableMetrics = Map.copyOf(Objects.requireNonNull(unavailableMetrics, "unavailableMetrics"));
    }

    public enum CollectionStatus {
        SUCCESS,
        PARTIAL_FAILURE,
        CONNECTION_FAILED
    }

    public enum MetricErrorCode {
        AUTH_FAILED,
        CONNECT_TIMEOUT,
        CONNECTION_REFUSED,
        QUERY_FAILED,
        INTERNAL_ERROR,
        UNKNOWN
    }

    public enum UnavailableReason {
        UNSUPPORTED,
        WARMUP,
        COUNTER_RESET,
        COLLECTION_FAILED,
        QUERY_FAILED
    }
}
