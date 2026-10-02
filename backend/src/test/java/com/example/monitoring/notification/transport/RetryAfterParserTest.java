package com.example.monitoring.notification.transport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterParserTest {
    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");

    @Test
    void preservesLargeValidDeltaInsteadOfFallingBackToAnEarlierRetry() {
        assertThat(RetryAfterParser.parse("90000", NOW)).contains(Duration.ofSeconds(90_000));
        assertThat(RetryAfterParser.parse("999999999999999999999999999999", NOW))
                .contains(Duration.ofSeconds(Long.MAX_VALUE));
        assertThat(DeliveryOutcome.rateLimited(Duration.ofSeconds(90_000)).retryAfter())
                .contains(Duration.ofSeconds(90_000));
    }

    @Test
    void acceptsHttpDateAndDistinguishesZeroFromMalformed() {
        assertThat(RetryAfterParser.parse("Sat, 3 Oct 2026 03:02:00 GMT", NOW))
                .contains(Duration.ofMinutes(2));
        assertThat(RetryAfterParser.parse("0", NOW)).contains(Duration.ZERO);
        assertThat(RetryAfterParser.parse("not-a-delay", NOW)).isEmpty();
    }
}
