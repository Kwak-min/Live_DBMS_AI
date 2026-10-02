package com.example.monitoring.risk.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class ContractChecks {

    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ContractChecks() {
    }

    static long safeId(long value, String field) {
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " must be a positive JavaScript-safe integer");
        }
        return value;
    }

    static Long nullableSafeId(Long value, String field) {
        if (value != null) {
            safeId(value, field);
        }
        return value;
    }

    static Instant millis(Instant value, String field) {
        return Objects.requireNonNull(value, field).truncatedTo(ChronoUnit.MILLIS);
    }

    static Instant nullableMillis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    static String text(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    static BigDecimal finiteNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }
}
