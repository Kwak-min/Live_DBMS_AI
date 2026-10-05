package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class HeartbeatHealthRegistry {

    static final Duration LIVENESS_WINDOW = Duration.ofSeconds(30);

    private final Clock clock;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public HeartbeatHealthRegistry(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public void accept(CollectorHeartbeatEvent event) {
        CollectorHeartbeatEvent required = java.util.Objects.requireNonNull(event, "event");
        Instant receivedAt = now();
        if (receivedAt.isAfter(required.timestamp().plus(LIVENESS_WINDOW))) {
            throw new InvalidStreamRecordException(
                    "STALE_HEARTBEAT",
                    "Heartbeat is older than the liveness window",
                    required.eventId());
        }
        entries.put(required.collectorId(), new Entry(required, receivedAt));
    }

    public boolean isLive(String collectorId) {
        return health(collectorId).map(HeartbeatHealth::live).orElse(false);
    }

    public Optional<HeartbeatHealth> health(String collectorId) {
        Entry entry = entries.get(collectorId);
        if (entry == null) {
            return Optional.empty();
        }
        CollectorHeartbeatEvent event = entry.event();
        return Optional.of(new HeartbeatHealth(
                event.collectorId(),
                now().isBefore(entry.receivedAt().plus(LIVENESS_WINDOW)),
                entry.receivedAt(),
                event.timestamp(),
                event.lastCycleStartedAt(),
                event.lastCycleCompletedAt(),
                event.cycleInProgress()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private record Entry(CollectorHeartbeatEvent event, Instant receivedAt) {
    }
}
