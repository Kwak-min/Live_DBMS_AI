package com.example.monitoring.notification.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "monitoring.notifications", name = "enabled", havingValue = "true")
public final class NotificationDeliveryLoop implements SmartLifecycle {

    private final NotificationDeliveryWorker worker;
    private final long intervalMillis;
    private volatile ScheduledExecutorService executor;
    private volatile boolean running;

    public NotificationDeliveryLoop(
            NotificationDeliveryWorker worker,
            @Value("${monitoring.notifications.delivery-interval-ms:1000}") long intervalMillis
    ) {
        if (intervalMillis < 1 || intervalMillis > 60_000) {
            throw new IllegalArgumentException(
                    "monitoring.notifications.delivery-interval-ms must be 1..60000");
        }
        this.worker = worker;
        this.intervalMillis = intervalMillis;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "notification-delivery-worker");
            thread.setDaemon(false);
            return thread;
        });
        running = true;
        executor.scheduleWithFixedDelay(this::runSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        ScheduledExecutorService owned = executor;
        executor = null;
        if (owned != null) {
            owned.shutdownNow();
            try {
                if (!owned.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Notification delivery thread did not stop within the shutdown window.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runSafely() {
        try {
            worker.runOnce();
        } catch (RuntimeException exception) {
            log.error("Notification delivery tick failed. cause={}",
                    exception.getClass().getSimpleName());
        }
    }
}
