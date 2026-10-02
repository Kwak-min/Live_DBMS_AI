package com.example.monitoring.notification.api;

import java.time.Instant;

public record PushSubscriptionResponse(
        long id,
        Instant createdAt,
        Instant updatedAt,
        Long expirationTime
) {
}
