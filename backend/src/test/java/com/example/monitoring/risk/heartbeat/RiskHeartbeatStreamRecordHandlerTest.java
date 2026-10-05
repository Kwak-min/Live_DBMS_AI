package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.StreamRecord;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RiskHeartbeatStreamRecordHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:30Z");

    @Test
    void verifiesSharedStartupAndUpdatesOnlyTheDiagnosticRegistry() {
        RiskStartupCoordinator startup = mock(RiskStartupCoordinator.class);
        HeartbeatHealthRegistry registry = new HeartbeatHealthRegistry(
                Clock.fixed(NOW, ZoneOffset.UTC));
        RiskHeartbeatStreamRecordHandler handler = new RiskHeartbeatStreamRecordHandler(
                new CollectorHeartbeatEventParser(new ObjectMapper()), registry, startup);

        handler.verifyPrerequisite();
        handler.handle(record(NOW.minusSeconds(30)));

        verify(startup).verifyPrerequisite();
        assertThat(registry.isLive("collector-a")).isTrue();
    }

    @Test
    void alreadyOldHeartbeatIsAnImmediateDlqSignalAndDoesNotEnterRegistry() {
        HeartbeatHealthRegistry registry = new HeartbeatHealthRegistry(
                Clock.fixed(NOW, ZoneOffset.UTC));
        RiskHeartbeatStreamRecordHandler handler = new RiskHeartbeatStreamRecordHandler(
                new CollectorHeartbeatEventParser(new ObjectMapper()),
                registry,
                mock(RiskStartupCoordinator.class));

        assertThatThrownBy(() -> handler.handle(record(NOW.minusSeconds(30).minusMillis(1))))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo("STALE_HEARTBEAT"));
        assertThat(registry.health("collector-a")).isEmpty();
    }

    private StreamRecord record(Instant timestamp) {
        String payload = """
                {"schemaVersion":1,
                 "eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "eventType":"CollectorHeartbeatEvent",
                 "publishedAt":"%s",
                 "collectorId":"collector-a",
                 "timestamp":"%s",
                 "lastCycleStartedAt":null,
                 "lastCycleCompletedAt":null,
                 "cycleInProgress":false}
                """.formatted(canonical(timestamp), canonical(timestamp));
        return new StreamRecord(
                "stream:collector-heartbeats",
                "1-0",
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private String canonical(Instant value) {
        return new java.time.format.DateTimeFormatterBuilder()
                .appendInstant(3)
                .toFormatter()
                .format(value);
    }
}
