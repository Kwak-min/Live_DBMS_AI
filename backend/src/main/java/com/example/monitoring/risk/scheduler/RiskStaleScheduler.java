package com.example.monitoring.risk.scheduler;

import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import com.example.monitoring.risk.persistence.StaleCandidate;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component("riskStaleScheduler")
@ConditionalOnProperty(prefix = "monitoring.risk", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RiskStaleScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RiskStaleScheduler.class);

    private final RiskJdbcStore store;
    private final RiskStaleTransaction transaction;
    private final RiskStartupCoordinator startup;
    private final Clock clock;
    private final Duration scanInterval;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile ScheduledExecutorService executor;

    @Autowired
    public RiskStaleScheduler(
            RiskJdbcStore store,
            RiskStaleTransaction transaction,
            RiskStartupCoordinator startup,
            Clock clock,
            @Value("${monitoring.risk.stale-scan-interval:1s}") Duration scanInterval,
            @Value("${monitoring.risk.stale-scan-batch-size:100}") int batchSize
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.startup = Objects.requireNonNull(startup, "startup");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scanInterval = requireInterval(scanInterval);
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("stale scan batch size must be 1..1000");
        }
        this.batchSize = batchSize;
    }

    RiskStaleScheduler(
            RiskJdbcStore store,
            RiskStaleTransaction transaction,
            RiskStartupCoordinator startup,
            Clock clock
    ) {
        this(store, transaction, startup, clock, Duration.ofSeconds(1), 100);
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        startup.verifyPrerequisite();
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "risk-stale-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor = created;
        running.set(true);
        try {
            created.scheduleAtFixedRate(
                    this::scanSafely,
                    0L,
                    scanInterval.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RuntimeException exception) {
            running.set(false);
            executor = null;
            created.shutdownNow();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) {
            return;
        }
        current.shutdownNow();
        try {
            current.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    private void scanSafely() {
        if (!running.get()) {
            return;
        }
        try {
            scanDueTargets();
        } catch (RuntimeException exception) {
            if (running.get()) {
                log.warn(
                        "Risk stale scan dependency failure; cause={}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void scanDueTargets() {
        Instant scannedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        while (running.get()) {
            List<StaleCandidate> candidates = store.findStaleCandidates(scannedAt, batchSize);
            int applied = 0;
            for (StaleCandidate candidate : candidates) {
                if (!running.get()) {
                    return;
                }
                try {
                    if (transaction.process(candidate, scannedAt)
                            == RiskStaleTransaction.Outcome.APPLIED) {
                        applied++;
                    }
                } catch (RiskPersistenceInvariantException exception) {
                    log.warn(
                            "Risk stale candidate invariant failure; databaseConfigId={}, cause={}",
                            candidate.databaseConfigId(),
                            exception.getClass().getSimpleName());
                }
            }
            if (candidates.size() < batchSize || applied == 0) {
                return;
            }
        }
    }

    private Duration requireInterval(Duration interval) {
        Duration required = Objects.requireNonNull(interval, "scanInterval");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException("stale scan interval must be positive");
        }
        return required;
    }
}
