package com.example.monitoring.risk.persistence;

public final class RiskPersistenceInvariantException extends RuntimeException {

    public RiskPersistenceInvariantException(String message) {
        super(message);
    }

    public RiskPersistenceInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
