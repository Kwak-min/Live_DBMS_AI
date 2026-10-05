package com.example.monitoring.notification.transport;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.math.BigInteger;
import java.util.Optional;

public final class RetryAfterParser {
    private static final BigInteger MAX_SECONDS = BigInteger.valueOf(Long.MAX_VALUE);

    private RetryAfterParser() { }

    public static Optional<Duration> parse(String value, Instant now) {
        if (value == null || now == null) {
            return Optional.empty();
        }
        String candidate = value.trim();
        if (candidate.matches("[0-9]+")) {
            BigInteger seconds = new BigInteger(candidate);
            if (seconds.compareTo(MAX_SECONDS) > 0) {
                seconds = MAX_SECONDS;
            }
            return Optional.of(Duration.ofSeconds(seconds.longValueExact()));
        }
        try {
            Instant retryAt = ZonedDateTime.parse(candidate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (!retryAt.isAfter(now)) {
                return Optional.of(Duration.ZERO);
            }
            return Optional.of(Duration.between(now, retryAt));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }
}
