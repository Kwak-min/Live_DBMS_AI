package com.example.monitoring.notification.transport;

public final class PinnedTransportException extends RuntimeException {
    public enum Kind {
        TIMEOUT,
        CONNECTION
    }

    private final Kind kind;

    public PinnedTransportException(Kind kind, Throwable cause) {
        super(kind == Kind.TIMEOUT ? "Notification transport timed out." : "Notification transport failed.", cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
