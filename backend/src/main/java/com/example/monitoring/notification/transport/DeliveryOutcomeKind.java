package com.example.monitoring.notification.transport;

public enum DeliveryOutcomeKind {
    SENT,
    RATE_LIMITED,
    RECIPIENT_GONE,
    REJECTED,
    PROVIDER_ERROR,
    TIMEOUT
}
