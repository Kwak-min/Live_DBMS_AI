package com.example.monitoring.realtime.stomp;

import com.example.monitoring.common.api.ApiException;

import java.util.Set;
import java.util.UUID;

record StompFailure(String code, String message, String requestId) {
    private static final Set<String> PUBLIC_CODES = Set.of(
            "AUTH_REQUIRED",
            "ACCESS_TOKEN_EXPIRED",
            "INVALID_TOKEN",
            "SESSION_REVOKED",
            "FORBIDDEN",
            "DATABASE_NOT_FOUND",
            "VALIDATION_ERROR"
    );

    static StompFailure of(String code) {
        String safeCode = PUBLIC_CODES.contains(code) ? code : "VALIDATION_ERROR";
        return new StompFailure(safeCode, messageFor(safeCode), UUID.randomUUID().toString());
    }

    static StompFailure from(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StompTransportException transportException) {
                return transportException.failure();
            }
            if (current instanceof ApiException apiException && PUBLIC_CODES.contains(apiException.getCode())) {
                return of(apiException.getCode());
            }
        }
        return of("VALIDATION_ERROR");
    }

    private static String messageFor(String code) {
        return switch (code) {
            case "AUTH_REQUIRED" -> "Access token is required.";
            case "ACCESS_TOKEN_EXPIRED" -> "Access token has expired.";
            case "INVALID_TOKEN" -> "Access token is invalid.";
            case "SESSION_REVOKED" -> "Authentication session is no longer active.";
            case "FORBIDDEN" -> "Access is forbidden.";
            case "DATABASE_NOT_FOUND" -> "Database was not found.";
            default -> "STOMP frame is invalid.";
        };
    }
}
