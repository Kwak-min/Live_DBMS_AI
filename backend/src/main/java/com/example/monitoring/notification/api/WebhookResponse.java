package com.example.monitoring.notification.api;

import java.time.Instant;

public record WebhookResponse(
        long id,
        String name,
        String provider,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
