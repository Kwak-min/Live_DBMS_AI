package com.example.monitoring.scheduler;

import com.example.monitoring.collector.DbMetricsCollector;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.database.security.DatabaseCredentialUnavailableException;
import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
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
        when(recorder.record(any(CollectorTarget.class), any())).thenReturn(Optional.empty());
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
        when(targetProvider.listEnabled()).thenReturn(List.of(summary(slow), summary(other)));
        when(targetProvider.getForCollection(1L)).thenReturn(Optional.of(slow));
        when(targetProvider.getForCollection(2L)).thenReturn(Optional.of(other));

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
        when(targetProvider.listEnabled()).thenReturn(List.of(summary(target)));
        when(targetProvider.getForCollection(3L)).thenReturn(Optional.of(target));
        when(collector.collectMetrics(target)).thenReturn(new MetricData());
        when(recorder.record(any(CollectorTarget.class), any())).thenThrow(new IllegalStateException("db down"));

        worker.executeCollectionCycle();
        verify(recorder, timeout(2000)).record(any(CollectorTarget.class), any());
        awaitReleased(3L);

        worker.executeCollectionCycle();
        verify(collector, timeout(2000).times(2)).collectMetrics(target);
    }

    @Test
    @DisplayName("One failed credential is recorded while another target still collects")
    void credentialFailureIsTargetScoped() {
        CollectorTarget healthy = target(5L);
        TargetMetadata failed = summary(target(4L));
        when(targetProvider.listEnabled()).thenReturn(List.of(failed, summary(healthy)));
        when(targetProvider.getForCollection(4L)).thenThrow(
                new DatabaseCredentialUnavailableException(4L, 1L, 1, 1,
                        new IllegalStateException("bad key")));
        when(targetProvider.getForCollection(5L)).thenReturn(Optional.of(healthy));
        MetricData failure = MetricData.builder().collectionStatus(CollectionStatus.CONNECTION_FAILED)
                .errorCode(MetricErrorCode.INTERNAL_ERROR).build();
        when(collector.credentialsUnavailable(4L)).thenReturn(failure);
        when(collector.collectMetrics(healthy)).thenReturn(new MetricData());

        worker.executeCollectionCycle();

        verify(recorder, timeout(2000)).record(failed, failure);
        verify(collector, timeout(2000)).collectMetrics(healthy);
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

    private static TargetMetadata summary(CollectorTarget target) {
        return new TargetMetadata(target.id(), target.configVersion(), target.name(), target.host(), target.port(),
                target.databaseName(), target.enabled());
    }
}
