package com.example.monitoring.notification.webpush;

import com.example.monitoring.notification.security.NotificationContentPolicy;
import com.example.monitoring.notification.transport.NotificationType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record WebPushMessage(long deliveryId, UUID incidentId, NotificationType type,
                             String title, String body, Instant sentAt) {
    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;

    public WebPushMessage {
        if (deliveryId < 1 || deliveryId > MAX_SAFE_ID) {
            throw new IllegalArgumentException("Invalid notification delivery ID.");
        }
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(type, "type");
        requireText(title, 100, "title");
        requireText(body, 300, "body");
        Objects.requireNonNull(sentAt, "sentAt");
        sentAt = sentAt.truncatedTo(ChronoUnit.MILLIS);
        NotificationContentPolicy.requireSafe(title, body);
    }

    private static void requireText(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException("Invalid Web Push " + field + '.');
        }
    }
}
