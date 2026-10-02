package com.example.monitoring.risk.scheduler;

import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.StaleCandidate;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskStaleSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:30Z");
    private RiskStaleScheduler scheduler;

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    void runsDueScanOnDedicatedNamedExecutorAfterStartupReset() throws Exception {
        RiskJdbcStore store = mock(RiskJdbcStore.class);
        RiskStaleTransaction transaction = mock(RiskStaleTransaction.class);
        RiskStartupCoordinator startup = mock(RiskStartupCoordinator.class);
        StaleCandidate due = new StaleCandidate(12L, NOW);
        CountDownLatch processed = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        when(store.findStaleCandidates(NOW, 100))
                .thenReturn(List.of(due), List.of());
        when(transaction.process(due, NOW)).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            processed.countDown();
            return RiskStaleTransaction.Outcome.APPLIED;
        });
        scheduler = new RiskStaleScheduler(
                store, transaction, startup, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.start();

        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName).hasValue("risk-stale-scheduler");
        assertThat(scheduler.isRunning()).isTrue();
        verify(startup).verifyPrerequisite();
    }

    @Test
    void prerequisiteFailurePreventsExecutorStartAndDatabaseScan() {
        RiskJdbcStore store = mock(RiskJdbcStore.class);
        RiskStaleTransaction transaction = mock(RiskStaleTransaction.class);
        RiskStartupCoordinator startup = mock(RiskStartupCoordinator.class);
        doThrow(new IllegalStateException("missing V4 tables"))
                .when(startup).verifyPrerequisite();
        scheduler = new RiskStaleScheduler(
                store, transaction, startup, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(scheduler::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing V4 tables");
        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(store, transaction);
    }
}
