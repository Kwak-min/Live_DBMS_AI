package com.example.monitoring.notification.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class DeliveryOutcome {
    private final DeliveryOutcomeKind kind;
    private final Duration retryAfter;

    private DeliveryOutcome(DeliveryOutcomeKind kind, Duration retryAfter) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind != DeliveryOutcomeKind.RATE_LIMITED && retryAfter != null) {
            throw new IllegalArgumentException("Retry delay is only valid for rate limiting.");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry delay must not be negative.");
        }
        this.retryAfter = retryAfter;
    }

    public static DeliveryOutcome of(DeliveryOutcomeKind kind) {
        return new DeliveryOutcome(kind, null);
    }

    public static DeliveryOutcome rateLimited(Duration retryAfter) {
        return new DeliveryOutcome(DeliveryOutcomeKind.RATE_LIMITED, retryAfter);
    }

    public DeliveryOutcomeKind kind() {
        return kind;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    @Override
    public String toString() {
        return "DeliveryOutcome[kind=" + kind + ", retryAfter=" + retryAfter().orElse(null) + ']';
    }
}
