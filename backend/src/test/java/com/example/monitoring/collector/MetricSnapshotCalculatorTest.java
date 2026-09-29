package com.example.monitoring.collector;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.domain.MetricUnavailableReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.example.monitoring.domain.MetricUnavailableReason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** docs/contract-examples.json의 warmup·정상·실제 0·부분 실패·실패 시나리오를 따른다. */
class MetricSnapshotCalculatorTest {

    private static final long TARGET = 12L;
    private static final long VERSION = 2L;
    private static final long SECOND = 1_000_000_000L;
    private static final Instant T0 = Instant.parse("2026-09-28T02:59:55.000Z");
    private static final Instant T5 = Instant.parse("2026-09-28T03:00:00.000Z");

    private final MetricSnapshotCalculator calculator = new MetricSnapshotCalculator();

    @Test
    @DisplayName("First observation is SUCCESS with derived metrics null and marked WARMUP")
    void firstObservationIsWarmup() {
        MetricData metric = connected(VERSION, T0, status(18, 3, 1000, 310, 100), 0);

        assertThat(metric.getCollectionStatus()).isEqualTo(CollectionStatus.SUCCESS);
        assertThat(metric.getErrorCode()).isNull();
        assertThat(metric.getActiveConnections()).isEqualTo(18L);
        assertThat(metric.getMaxConnections()).isEqualTo(151L);
        assertThat(metric.getSlowQueries()).isEqualTo(310L);
        assertThat(metric.getQps()).isNull();
        assertThat(metric.getSlowQueriesDelta()).isNull();
        assertThat(metric.getSlowQueriesPerSecond()).isNull();
        assertThat(metric.getMetricWindowSeconds()).isNull();
        assertThat(metric.getUnavailableMetrics()).containsExactly(
                entry("cpuUsage", UNSUPPORTED), entry("memoryUsage", UNSUPPORTED),
                entry("qps", WARMUP), entry("slowQueriesDelta", WARMUP),
                entry("slowQueriesPerSecond", WARMUP), entry("metricWindowSeconds", WARMUP));
        assertThat(metric.getTimestamp()).isEqualTo(T0);
        assertThat(metric.getCollectionAttemptTime()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Next valid observation derives qps and slow query rate from the real elapsed time")
    void derivesFromElapsedTime() {
        connected(VERSION, T0, status(18, 3, 1000, 308, 100), 0);

        MetricData metric = connected(VERSION, T5, status(18, 3, 1121, 310, 105), 5 * SECOND);

        assertThat(metric.getQps()).isEqualTo(24.2);
        assertThat(metric.getSlowQueriesDelta()).isEqualTo(2L);
        assertThat(metric.getSlowQueriesPerSecond()).isEqualTo(0.4);
        assertThat(metric.getMetricWindowSeconds()).isEqualTo(5.0);
        assertThat(metric.getUnavailableMetrics()).containsOnlyKeys("cpuUsage", "memoryUsage");
    }

    @Test
    @DisplayName("Measured zero stays zero and is not reported as unavailable")
    void measuredZeroIsKept() {
        connected(VERSION, T0, status(0, 0, 500, 0, 100), 0);

        MetricData metric = connected(VERSION, T5, status(0, 0, 500, 0, 105), 5 * SECOND);

        assertThat(metric.getActiveConnections()).isZero();
        assertThat(metric.getThreadsRunning()).isZero();
        assertThat(metric.getQps()).isZero();
        assertThat(metric.getSlowQueriesDelta()).isZero();
        assertThat(metric.getSlowQueriesPerSecond()).isZero();
        assertThat(metric.getStorageBytes()).isEqualTo(104857600L);
        assertThat(metric.getUnavailableMetrics()).containsOnlyKeys("cpuUsage", "memoryUsage");
    }

    @Test
    @DisplayName("Counter or uptime decrease resets the baseline and reports COUNTER_RESET")
    void counterDecreaseIsReset() {
        connected(VERSION, T0, status(18, 3, 5000, 300, 1000), 0);

        MetricData reset = connected(VERSION, T5, status(18, 3, 40, 0, 5), 5 * SECOND);
        MetricData next = connected(VERSION, T5.plusSeconds(5), status(18, 3, 90, 1, 10), 10 * SECOND);

        assertThat(reset.getCollectionStatus()).isEqualTo(CollectionStatus.SUCCESS);
        assertThat(reset.getQps()).isNull();
        assertThat(reset.getUnavailableMetrics()).containsEntry("qps", COUNTER_RESET)
                .containsEntry("metricWindowSeconds", COUNTER_RESET);
        assertThat(next.getQps()).isEqualTo(10.0);
        assertThat(next.getSlowQueriesDelta()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A changed configVersion starts a new WARMUP instead of comparing with the old baseline")
    void configVersionChangeIsWarmup() {
        connected(1L, T0, status(18, 3, 1000, 300, 100), 0);

        MetricData metric = connected(2L, T5, status(18, 3, 1100, 301, 105), 5 * SECOND);

        assertThat(metric.getQps()).isNull();
        assertThat(metric.getUnavailableMetrics()).containsEntry("qps", WARMUP);
    }

    @Test
    @DisplayName("Missing required source is PARTIAL_FAILURE with QUERY_FAILED; successful fields are kept")
    void partialFailureKeepsSuccessfulFields() {
        connected(VERSION, T0, status(18, 3, 1000, 310, 100), 0);
        Map<String, String> status = status(20, 4, 0, 320, 105);
        status.remove(MetricSnapshotCalculator.QUERIES);

        MetricData metric = calculator.connected(TARGET, VERSION, T5, 15, status, variables(), null, 5 * SECOND);

        assertThat(metric.getCollectionStatus()).isEqualTo(CollectionStatus.PARTIAL_FAILURE);
        assertThat(metric.getErrorCode()).isEqualTo(MetricErrorCode.QUERY_FAILED);
        assertThat(metric.getErrorMessage()).isNotBlank();
        assertThat(metric.getActiveConnections()).isEqualTo(20L);
        assertThat(metric.getSlowQueries()).isEqualTo(320L);
        assertThat(metric.getQps()).isNull();
        assertThat(metric.getStorageBytes()).isNull();
        assertThat(metric.getUnavailableMetrics()).containsExactly(
                entry("cpuUsage", UNSUPPORTED), entry("memoryUsage", UNSUPPORTED),
                entry("qps", QUERY_FAILED), entry("slowQueriesDelta", QUERY_FAILED),
                entry("slowQueriesPerSecond", QUERY_FAILED), entry("metricWindowSeconds", QUERY_FAILED),
                entry("storageBytes", QUERY_FAILED));
    }

    @Test
    @DisplayName("Missing optional storage alone does not turn SUCCESS into a failure")
    void storageFailureKeepsSuccess() {
        MetricData metric = calculator.connected(TARGET, VERSION, T0, 12,
                status(18, 3, 1000, 310, 100), variables(), null, 0);

        assertThat(metric.getCollectionStatus()).isEqualTo(CollectionStatus.SUCCESS);
        assertThat(metric.getUnavailableMetrics()).containsEntry("storageBytes", QUERY_FAILED);
    }

    @Test
    @DisplayName("Connection failure nulls every metric, keeps response time and resets the baseline")
    void connectionFailureNullsMetrics() {
        connected(VERSION, T0, status(18, 3, 1000, 310, 100), 0);

        MetricData failed = calculator.connectionFailed(TARGET, T5, 8, MetricErrorCode.AUTH_FAILED, "인증 실패");
        MetricData afterFailure = connected(VERSION, T5.plusSeconds(5), status(18, 3, 1100, 311, 110), 10 * SECOND);

        assertThat(failed.getCollectionStatus()).isEqualTo(CollectionStatus.CONNECTION_FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(MetricErrorCode.AUTH_FAILED);
        assertThat(failed.getResponseTimeMs()).isEqualTo(8L);
        assertThat(failed.getActiveConnections()).isNull();
        assertThat(failed.getMaxConnections()).isNull();
        assertThat(failed.getSlowQueries()).isNull();
        assertThat(failed.getStorageBytes()).isNull();
        assertThat(failed.getUnavailableMetrics())
                .containsEntry("cpuUsage", UNSUPPORTED)
                .containsEntry("activeConnections", COLLECTION_FAILED)
                .containsEntry("qps", COLLECTION_FAILED)
                .containsEntry("storageBytes", COLLECTION_FAILED)
                .hasSize(11);
        assertThat(afterFailure.getUnavailableMetrics()).containsEntry("qps", WARMUP);
    }

    @Test
    @DisplayName("Negative or non-numeric source values are rejected as QUERY_FAILED")
    void invalidValuesAreRejected() {
        Map<String, String> status = status(18, 3, 1000, 310, 100);
        status.put(MetricSnapshotCalculator.THREADS_CONNECTED, "-1");
        status.put(MetricSnapshotCalculator.THREADS_RUNNING, "abc");

        MetricData metric = calculator.connected(TARGET, VERSION, T0, 12, status, variables(), 1L, 0);

        assertThat(metric.getCollectionStatus()).isEqualTo(CollectionStatus.PARTIAL_FAILURE);
        assertThat(metric.getUnavailableMetrics())
                .containsEntry("activeConnections", MetricUnavailableReason.QUERY_FAILED)
                .containsEntry("threadsRunning", MetricUnavailableReason.QUERY_FAILED);
    }

    private MetricData connected(long configVersion, Instant at, Map<String, String> status, long nanos) {
        return calculator.connected(TARGET, configVersion, at, 12, status, variables(), 104857600L, nanos);
    }

    private static Map<String, String> status(long connected, long running, long queries, long slow, long uptime) {
        Map<String, String> status = new HashMap<>();
        status.put(MetricSnapshotCalculator.THREADS_CONNECTED, Long.toString(connected));
        status.put(MetricSnapshotCalculator.THREADS_RUNNING, Long.toString(running));
        status.put(MetricSnapshotCalculator.QUERIES, Long.toString(queries));
        status.put(MetricSnapshotCalculator.SLOW_QUERIES, Long.toString(slow));
        status.put(MetricSnapshotCalculator.UPTIME, Long.toString(uptime));
        return status;
    }

    private static Map<String, String> variables() {
        return Map.of(MetricSnapshotCalculator.MAX_CONNECTIONS, "151");
    }
}
