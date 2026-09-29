package com.example.monitoring.scheduler;

import com.example.monitoring.collector.DbMetricsCollector;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.metric.MetricCollectionRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricSchedulerWorkerTest {

    private final TargetProvider targetProvider = mock(TargetProvider.class);
    private final DbMetricsCollector collector = mock(DbMetricsCollector.class);
    private final MetricCollectionRecorder recorder = mock(MetricCollectionRecorder.class);
    private MetricSchedulerWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MetricSchedulerWorker(targetProvider, collector, recorder);
        when(recorder.record(any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    @Test
    @DisplayName("A target whose previous collection is still running is skipped instead of collected twice")
    void runningTargetIsSkipped() throws Exception {
        CollectorTarget slow = target(1L);
        CollectorTarget other = target(2L);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch slowStarted = new CountDownLatch(1);
        when(collector.collectMetrics(slow)).thenAnswer(invocation -> {
            slowStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new MetricData();
        });
        when(collector.collectMetrics(other)).thenReturn(new MetricData());
        when(targetProvider.listEnabled()).thenReturn(List.of(slow, other));

        worker.executeCollectionCycle();
        assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
        awaitReleased(2L);
        worker.executeCollectionCycle();

        verify(collector, timeout(2000).times(2)).collectMetrics(other);
        verify(collector, after(200).times(1)).collectMetrics(slow);
        assertThat(worker.isInFlight(1L)).isTrue();

        release.countDown();
        awaitReleased(1L);
        worker.executeCollectionCycle();
        verify(collector, timeout(2000).times(2)).collectMetrics(slow);
    }

    @Test
    @DisplayName("Failure to load targets skips the cycle without throwing")
    void targetLoadFailureIsContained() {
        when(targetProvider.listEnabled()).thenThrow(new IllegalStateException("decrypt failed"));

        assertThatCode(worker::executeCollectionCycle).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Recording failure (e.g. PostgreSQL down) is logged and releases the target for the next tick")
    void recordFailureReleasesTarget() throws Exception {
        CollectorTarget target = target(3L);
        when(targetProvider.listEnabled()).thenReturn(List.of(target));
        when(collector.collectMetrics(target)).thenReturn(new MetricData());
        when(recorder.record(any(), any())).thenThrow(new IllegalStateException("db down"));

        worker.executeCollectionCycle();
        verify(recorder, timeout(2000)).record(any(), any());
        awaitReleased(3L);

        worker.executeCollectionCycle();
        verify(collector, timeout(2000).times(2)).collectMetrics(target);
    }

    private void awaitReleased(long id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (worker.isInFlight(id) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(worker.isInFlight(id)).isFalse();
    }

    private static CollectorTarget target(long id) {
        return new CollectorTarget(id, 1L, "db" + id, "127.0.0.1", 13306, null, "u", "p", true);
    }
}
