package com.example.monitoring.realtime.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RealtimeMetricMessage(
        int schemaVersion,
        UUID eventId,
        String eventType,
        long databaseConfigId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant publishedAt,
        MetricData data
) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "MetricUpdated";

    public RealtimeMetricMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(data, "data");
    }

    public static RealtimeMetricMessage from(MetricCollectedPayloadV1 payload) {
        Objects.requireNonNull(payload, "payload");
        return new RealtimeMetricMessage(
                SCHEMA_VERSION,
                payload.eventId(),
                EVENT_TYPE,
                payload.databaseConfigId(),
                payload.publishedAt(),
                MetricData.from(payload));
    }

    public record MetricData(
            long id,
            long databaseConfigId,
            long configVersion,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant timestamp,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant collectionAttemptTime,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
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
            MetricCollectedPayloadV1.CollectionStatus collectionStatus,
            MetricCollectedPayloadV1.MetricErrorCode errorCode,
            String errorMessage,
            Map<String, MetricCollectedPayloadV1.UnavailableReason> unavailableMetrics
    ) {

        public MetricData {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(collectionAttemptTime, "collectionAttemptTime");
            Objects.requireNonNull(collectionStatus, "collectionStatus");
            unavailableMetrics = Map.copyOf(Objects.requireNonNull(unavailableMetrics, "unavailableMetrics"));
        }

        private static MetricData from(MetricCollectedPayloadV1 payload) {
            return new MetricData(
                    payload.metricId(),
                    payload.databaseConfigId(),
                    payload.configVersion(),
                    payload.timestamp(),
                    payload.collectionAttemptTime(),
                    payload.lastSuccessAt(),
                    payload.cpuUsage(),
                    payload.memoryUsage(),
                    payload.activeConnections(),
                    payload.maxConnections(),
                    payload.qps(),
                    payload.slowQueries(),
                    payload.slowQueriesDelta(),
                    payload.slowQueriesPerSecond(),
                    payload.metricWindowSeconds(),
                    payload.threadsRunning(),
                    payload.storageBytes(),
                    payload.responseTimeMs(),
                    payload.collectionStatus(),
                    payload.errorCode(),
                    payload.errorMessage(),
                    payload.unavailableMetrics());
        }
    }
}
