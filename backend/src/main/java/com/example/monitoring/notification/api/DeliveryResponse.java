package com.example.monitoring.notification.api;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        long id,
        UUID incidentId,
        long incidentVersion,
        NotificationChannel channel,
        long recipientId,
        DeliveryStatus status,
        int attemptCount,
        String lastErrorCode,
        Instant createdAt,
        Instant sentAt
) {
}
