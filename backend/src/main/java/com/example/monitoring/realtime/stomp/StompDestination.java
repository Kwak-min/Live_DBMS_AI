package com.example.monitoring.realtime.stomp;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record StompDestination(Type type, Long databaseConfigId) {
    private static final String ERRORS = "/user/queue/errors";
    private static final Pattern TARGET = Pattern.compile(
            "^/topic/databases/([1-9][0-9]*)/(metrics|status|incidents)$");
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    static StompDestination parse(String value) {
        if (ERRORS.equals(value)) {
            return new StompDestination(Type.ERRORS, null);
        }
        Matcher matcher = TARGET.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        String rawId = matcher.group(1);
        if (rawId.length() > 16) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        BigInteger parsed = new BigInteger(rawId);
        if (parsed.compareTo(MAX_SAFE_INTEGER) > 0) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        Type type = switch (matcher.group(2)) {
            case "metrics" -> Type.METRICS;
            case "status" -> Type.STATUS;
            case "incidents" -> Type.INCIDENTS;
            default -> throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        };
        return new StompDestination(type, parsed.longValueExact());
    }

    enum Type {
        ERRORS,
        METRICS,
        STATUS,
        INCIDENTS
    }
}
