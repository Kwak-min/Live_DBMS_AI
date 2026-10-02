package com.example.monitoring.notification.transport;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class HostResolutionExecutor implements AutoCloseable {
    private static final int MAX_CONCURRENT_RESOLUTIONS = 4;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    private final ThreadPoolExecutor executor;

    public HostResolutionExecutor() {
        this(MAX_CONCURRENT_RESOLUTIONS);
    }

    HostResolutionExecutor(int maximumConcurrency) {
        if (maximumConcurrency < 1 || maximumConcurrency > MAX_CONCURRENT_RESOLUTIONS) {
            throw new IllegalArgumentException("Invalid notification DNS concurrency.");
        }
        executor = new ThreadPoolExecutor(0, maximumConcurrency, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable,
                            "notification-dns-resolver-" + THREAD_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    Future<InetAddress[]> submit(Callable<InetAddress[]> resolution) {
        return executor.submit(resolution);
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
