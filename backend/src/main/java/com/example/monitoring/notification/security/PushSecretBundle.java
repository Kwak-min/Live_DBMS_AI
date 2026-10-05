package com.example.monitoring.notification.security;

public final class PushSecretBundle {
    private final String endpoint;
    private final String p256dh;
    private final String auth;

    public PushSecretBundle(String endpoint, String p256dh, String auth) {
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public String endpoint() {
        return endpoint;
    }

    public String p256dh() {
        return p256dh;
    }

    public String auth() {
        return auth;
    }

    @Override
    public String toString() {
        return "PushSecretBundle[redacted]";
    }
}
