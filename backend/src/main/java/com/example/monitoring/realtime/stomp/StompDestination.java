package com.example.monitoring.realtime.stomp;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record StompDestination(Type type, Long databaseConfigId) {
    private static final String ERRORS = "/user/queue/errors";
    private static final String GLOBAL_INCIDENTS = "/topic/incidents";
    private static final Pattern TARGET_DATABASES = Pattern.compile(
            "^/topic/databases/([1-9][0-9]*)/(metrics|status|incidents)$");
    private static final Pattern TARGET_DIRECT = Pattern.compile(
            "^/topic/(metrics|status|incidents)/([1-9][0-9]*)$");
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    static StompDestination parse(String value) {
        if (ERRORS.equals(value)) {
            return new StompDestination(Type.ERRORS, null);
        }
        if (GLOBAL_INCIDENTS.equals(value)) {
            return new StompDestination(Type.INCIDENTS, null);
        }
        Matcher matcher = TARGET_DATABASES.matcher(value == null ? "" : value);
        String rawId;
        String typeStr;
        if (matcher.matches()) {
            rawId = matcher.group(1);
            typeStr = matcher.group(2);
        } else {
            matcher = TARGET_DIRECT.matcher(value == null ? "" : value);
            if (!matcher.matches()) {
                throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
            }
            typeStr = matcher.group(1);
            rawId = matcher.group(2);
        }
        if (rawId.length() > 16) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        BigInteger parsed = new BigInteger(rawId);
        if (parsed.compareTo(MAX_SAFE_INTEGER) > 0) {
            throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
        }
        Type type = switch (typeStr) {
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
