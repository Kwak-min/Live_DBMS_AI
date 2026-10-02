package com.example.monitoring.notification.webpush;

public final class WebPushRecipient {
    private final String endpoint;
    private final String p256dh;
    private final String auth;

    public WebPushRecipient(String endpoint, String p256dh, String auth) {
        if (endpoint == null || p256dh == null || auth == null) {
            throw new IllegalArgumentException("Web Push recipient fields are required.");
        }
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
        return "WebPushRecipient[redacted]";
    }
}
