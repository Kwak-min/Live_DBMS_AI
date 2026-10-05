package com.example.monitoring.notification.session;

import java.time.Instant;
import java.util.UUID;

public interface PushSubscriptionLifecyclePort {
    int deactivateBySession(UUID sessionId, Instant now);

    int deactivateByUser(long userId, Instant now);

    int deactivateSessionsEligibleForRetention(Instant expiresBefore, Instant now);
}
