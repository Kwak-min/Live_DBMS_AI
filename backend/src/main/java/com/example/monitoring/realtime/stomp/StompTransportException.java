package com.example.monitoring.realtime.stomp;

final class StompTransportException extends RuntimeException {
    private final StompFailure failure;

    StompTransportException(StompFailure failure) {
        super(failure.code());
        this.failure = failure;
    }

    StompFailure failure() {
        return failure;
    }
}
