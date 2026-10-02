package com.example.monitoring.notification.slack;

import com.example.monitoring.notification.security.NotificationContentPolicy;
import com.example.monitoring.notification.transport.NotificationType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public record SlackMessage(String severity, String displayName, String ruleName,
                           NotificationType type, Instant occurredAt, UUID incidentId) {
    public SlackMessage {
        severity = text(severity, 32, "severity");
        displayName = text(displayName, 100, "displayName");
        ruleName = text(ruleName, 100, "ruleName");
        NotificationContentPolicy.requireSafe(severity, displayName, ruleName);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(incidentId, "incidentId");
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MILLIS);
    }

    private static String text(String value, int maxCodePoints, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid Slack " + field + '.');
        }
        return value.trim();
    }
}
