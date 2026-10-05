package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatHealthRegistryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:30Z");

    @Test
    void payloadAgeBoundaryAndReceiptTimeExpiryAreIndependent() {
        AdjustableClock clock = new AdjustableClock(NOW);
        HeartbeatHealthRegistry registry = new HeartbeatHealthRegistry(clock);

        registry.accept(event("collector-a", NOW.minusSeconds(30)));
        assertThat(registry.isLive("collector-a")).isTrue();

        clock.set(NOW.plusSeconds(29).plusMillis(999));
        assertThat(registry.isLive("collector-a")).isTrue();
        clock.set(NOW.plusSeconds(30));
        assertThat(registry.isLive("collector-a")).isFalse();

        clock.set(NOW);
        assertThatThrownBy(() -> registry.accept(
                event("collector-old", NOW.minusSeconds(30).minusMillis(1))))
                .isInstanceOf(InvalidStreamRecordException.class)
                .extracting(exception -> ((InvalidStreamRecordException) exception).reasonCode())
                .isEqualTo("STALE_HEARTBEAT");
        assertThat(registry.isLive("collector-old")).isFalse();
    }

    @Test
    void serverReceiptTimeControlsLivenessInsteadOfPayloadTimestamp() {
        AdjustableClock clock = new AdjustableClock(NOW);
        HeartbeatHealthRegistry registry = new HeartbeatHealthRegistry(clock);
        registry.accept(event("collector-a", NOW.minusSeconds(29)));

        clock.set(NOW.plusSeconds(20));
        registry.accept(event("collector-a", NOW.plusSeconds(20)));
        clock.set(NOW.plusSeconds(49));

        HeartbeatHealth health = registry.health("collector-a").orElseThrow();
        assertThat(health.live()).isTrue();
        assertThat(health.receivedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(health.eventTimestamp()).isEqualTo(NOW.plusSeconds(20));
    }

    private CollectorHeartbeatEvent event(String collectorId, Instant timestamp) {
        return new CollectorHeartbeatEvent(
                UUID.randomUUID(),
                timestamp,
                collectorId,
                timestamp,
                timestamp.minusSeconds(5),
                timestamp.minusSeconds(1),
                false);
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        private AdjustableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            current.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
