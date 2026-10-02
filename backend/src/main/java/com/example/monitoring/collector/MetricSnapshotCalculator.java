package com.example.monitoring.collector;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.domain.MetricUnavailableReason;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 원본 관측값을 v1 Metric 스냅샷으로 변환한다 (docs/events.md 1절).
 * 대상별로 직전 유효 관측(Queries/Slow_queries/Uptime)을 기억해 qps·slowQueriesDelta 등 파생 지표를 계산한다.
 * 첫 관측·설정 버전 변경은 WARMUP, 카운터/Uptime 감소는 COUNTER_RESET, 중간 실패는 기준을 초기화한다.
 * 파생 지표를 0으로 보충하지 않는다. lastSuccessAt과 configVersion 저장은 기록 단계에서 채운다.
 */
public class MetricSnapshotCalculator {

    static final String THREADS_CONNECTED = "Threads_connected";
    static final String THREADS_RUNNING = "Threads_running";
    static final String QUERIES = "Queries";
    static final String SLOW_QUERIES = "Slow_queries";
    static final String UPTIME = "Uptime";
    static final String MAX_CONNECTIONS = "max_connections";

    private static final String PARTIAL_FAILURE_MESSAGE = "일부 필수 지표를 조회하지 못했습니다.";
    private static final String INTERNAL_ERROR_MESSAGE = "대상 DB 수집 중 내부 오류가 발생했습니다.";
    private static final String CREDENTIALS_UNAVAILABLE_MESSAGE = "저장된 대상 DB 접속 정보를 읽지 못했습니다.";

    private final Map<Long, Baseline> baselines = new ConcurrentHashMap<>();

    /** 직전 유효 관측. observedNanos는 실제 경과 시간 계산용 단조 시계 값이다. */
    record Baseline(long configVersion, long queries, long slowQueries, long uptime, long observedNanos) {
    }

    /**
     * 연결에 성공한 관측을 변환한다. 조회에 실패한 원본은 map에 없거나 숫자가 아니다.
     *
     * @param storageBytes 선택 지표. 조회 실패면 null
     */
    public MetricData connected(long targetId, long configVersion, Instant startedAt, long responseTimeMs,
                                Map<String, String> status, Map<String, String> variables, Long storageBytes,
                                long observedNanos) {
        Map<String, MetricUnavailableReason> unavailable = baseUnavailable();
        Long activeConnections = parse(status, THREADS_CONNECTED);
        Long maxConnections = parse(variables, MAX_CONNECTIONS);
        Long threadsRunning = parse(status, THREADS_RUNNING);
        Long slowQueries = parse(status, SLOW_QUERIES);
        Long queries = parse(status, QUERIES);
        Long uptime = parse(status, UPTIME);

        boolean requiredComplete = activeConnections != null && maxConnections != null && threadsRunning != null
                && slowQueries != null && queries != null && uptime != null;

        markIfNull(unavailable, "activeConnections", activeConnections, MetricUnavailableReason.QUERY_FAILED);
        markIfNull(unavailable, "maxConnections", maxConnections, MetricUnavailableReason.QUERY_FAILED);
        markIfNull(unavailable, "slowQueries", slowQueries, MetricUnavailableReason.QUERY_FAILED);

        Derived derived = derive(targetId, configVersion, queries, slowQueries, uptime, observedNanos);
        if (derived.reason() != null) {
            unavailable.put("qps", derived.reason());
            unavailable.put("slowQueriesDelta", derived.reason());
            unavailable.put("slowQueriesPerSecond", derived.reason());
            unavailable.put("metricWindowSeconds", derived.reason());
        }
        markIfNull(unavailable, "threadsRunning", threadsRunning, MetricUnavailableReason.QUERY_FAILED);
        markIfNull(unavailable, "storageBytes", storageBytes, MetricUnavailableReason.QUERY_FAILED);

        return MetricData.builder()
                .timestamp(startedAt)
                .collectionAttemptTime(startedAt)
                .activeConnections(activeConnections)
                .maxConnections(maxConnections)
                .threadsRunning(threadsRunning)
                .slowQueries(slowQueries)
                .qps(derived.qps())
                .slowQueriesDelta(derived.slowQueriesDelta())
                .slowQueriesPerSecond(derived.slowQueriesPerSecond())
                .metricWindowSeconds(derived.windowSeconds())
                .storageBytes(storageBytes)
                .responseTimeMs(responseTimeMs)
                .collectionStatus(requiredComplete ? CollectionStatus.SUCCESS : CollectionStatus.PARTIAL_FAILURE)
                .errorCode(requiredComplete ? null : MetricErrorCode.QUERY_FAILED)
                .errorMessage(requiredComplete ? null : PARTIAL_FAILURE_MESSAGE)
                .unavailableMetrics(unavailable)
                .build();
    }

    /** 연결 자체에 실패한 관측. 원본·파생 수치는 모두 null이며 파생 기준을 초기화한다. */
    public MetricData connectionFailed(long targetId, Instant startedAt, long responseTimeMs,
                                       MetricErrorCode errorCode, String errorMessage) {
        return failed(targetId, startedAt, responseTimeMs, CollectionStatus.CONNECTION_FAILED, errorCode, errorMessage);
    }

    /**
     * 저장된 접속 정보를 복호화하지 못해 접속을 시도조차 못 한 관측. 대상에 접속할 수 없으므로 CONNECTION_FAILED로
     * 기록해 C가 DOWN·CONNECTION_FAILURE로 드러내게 하고, 원인은 errorCode=INTERNAL_ERROR로 대상 장애와 구분한다.
     */
    public MetricData credentialsUnavailable(long targetId, Instant startedAt) {
        return failed(targetId, startedAt, 0, CollectionStatus.CONNECTION_FAILED, MetricErrorCode.INTERNAL_ERROR,
                CREDENTIALS_UNAVAILABLE_MESSAGE);
    }

    /** 연결 후 예기치 못한 내부 오류. 부분 결과를 신뢰하지 않고 모두 null로 둔다. */
    public MetricData internalError(long targetId, Instant startedAt, long responseTimeMs, boolean connected) {
        CollectionStatus status = connected ? CollectionStatus.PARTIAL_FAILURE : CollectionStatus.CONNECTION_FAILED;
        return failed(targetId, startedAt, responseTimeMs, status, MetricErrorCode.INTERNAL_ERROR,
                INTERNAL_ERROR_MESSAGE);
    }

    private MetricData failed(long targetId, Instant startedAt, long responseTimeMs, CollectionStatus status,
                              MetricErrorCode errorCode, String errorMessage) {
        baselines.remove(targetId);
        Map<String, MetricUnavailableReason> unavailable = baseUnavailable();
        for (String metric : new String[]{"activeConnections", "maxConnections", "qps", "slowQueries",
                "slowQueriesDelta", "slowQueriesPerSecond", "metricWindowSeconds", "threadsRunning", "storageBytes"}) {
            unavailable.put(metric, MetricUnavailableReason.COLLECTION_FAILED);
        }
        return MetricData.builder()
                .timestamp(startedAt)
                .collectionAttemptTime(startedAt)
                .responseTimeMs(responseTimeMs)
                .collectionStatus(status)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .unavailableMetrics(unavailable)
                .build();
    }

    private record Derived(Double qps, Long slowQueriesDelta, Double slowQueriesPerSecond, Double windowSeconds,
                           MetricUnavailableReason reason) {
        static Derived unavailable(MetricUnavailableReason reason) {
            return new Derived(null, null, null, null, reason);
        }
    }

    private Derived derive(long targetId, long configVersion, Long queries, Long slowQueries, Long uptime,
                           long observedNanos) {
        if (queries == null || slowQueries == null || uptime == null) {
            baselines.remove(targetId);
            return Derived.unavailable(MetricUnavailableReason.QUERY_FAILED);
        }
        Baseline previous = baselines.put(targetId,
                new Baseline(configVersion, queries, slowQueries, uptime, observedNanos));
        if (previous == null || previous.configVersion() != configVersion) {
            return Derived.unavailable(MetricUnavailableReason.WARMUP);
        }
        if (queries < previous.queries() || slowQueries < previous.slowQueries() || uptime < previous.uptime()) {
            return Derived.unavailable(MetricUnavailableReason.COUNTER_RESET);
        }
        double windowSeconds = (observedNanos - previous.observedNanos()) / 1_000_000_000.0;
        if (windowSeconds <= 0) {
            return Derived.unavailable(MetricUnavailableReason.WARMUP);
        }
        long slowDelta = slowQueries - previous.slowQueries();
        return new Derived(
                round3((queries - previous.queries()) / windowSeconds),
                slowDelta,
                round3(slowDelta / windowSeconds),
                round3(windowSeconds),
                null);
    }

    private static Map<String, MetricUnavailableReason> baseUnavailable() {
        Map<String, MetricUnavailableReason> unavailable = new LinkedHashMap<>();
        unavailable.put("cpuUsage", MetricUnavailableReason.UNSUPPORTED);
        unavailable.put("memoryUsage", MetricUnavailableReason.UNSUPPORTED);
        return unavailable;
    }

    private static void markIfNull(Map<String, MetricUnavailableReason> unavailable, String metric, Object value,
                                   MetricUnavailableReason reason) {
        if (value == null) {
            unavailable.put(metric, reason);
        }
    }

    /** 유한한 비음수 정수만 인정한다. */
    private static Long parse(Map<String, String> values, String key) {
        String raw = values.get(key);
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
