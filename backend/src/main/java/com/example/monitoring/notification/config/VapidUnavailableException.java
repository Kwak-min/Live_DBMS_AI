package com.example.monitoring.notification.config;

public final class VapidUnavailableException extends IllegalStateException {
    public VapidUnavailableException() {
        super("Web Push signing configuration is unavailable.");
    }
}
