package com.example.monitoring.notification.webpush;

public final class WebPushPreparationException extends RuntimeException {
    public WebPushPreparationException(Throwable cause) {
        super("Unable to prepare Web Push request.", cause);
    }
}
