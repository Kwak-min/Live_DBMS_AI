package com.example.monitoring.common.stream;

import java.time.Duration;

public record StreamWorkerSpec(
        String sourceStream,
        String consumerGroup,
        String threadName,
        String deadLetterStream,
        Duration reclaimMinIdle,
        Duration reclaimInterval
) {

    public static final Duration DEFAULT_RECLAIM_MIN_IDLE = Duration.ofSeconds(60);
    public static final Duration DEFAULT_RECLAIM_INTERVAL = Duration.ofSeconds(30);

    public StreamWorkerSpec(
            String sourceStream,
            String consumerGroup,
            String threadName,
            String deadLetterStream
    ) {
        this(
                sourceStream,
                consumerGroup,
                threadName,
                deadLetterStream,
                DEFAULT_RECLAIM_MIN_IDLE,
                DEFAULT_RECLAIM_INTERVAL);
    }

    public StreamWorkerSpec {
        requireText(sourceStream, "sourceStream");
        requireText(consumerGroup, "consumerGroup");
        requireText(threadName, "threadName");
        requireText(deadLetterStream, "deadLetterStream");
        if (reclaimMinIdle == null || reclaimMinIdle.isNegative()) {
            throw new IllegalArgumentException("reclaimMinIdle must not be negative");
        }
        if (reclaimInterval == null || reclaimInterval.isZero() || reclaimInterval.isNegative()) {
            throw new IllegalArgumentException("reclaimInterval must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
