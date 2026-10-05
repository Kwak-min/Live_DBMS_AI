package com.example.monitoring.partc.api;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PartCQueryWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:34:56.789Z");
    private final PartCQueryValidator validator =
            new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsDefault24HoursAndExact30DayHalfOpenRange() {
        PartCQueryWindow defaultWindow = validator.window(null, null);

        assertThat(defaultWindow.start()).isEqualTo(Instant.parse("2026-09-27T12:34:56.789Z"));
        assertThat(defaultWindow.end()).isEqualTo(NOW);

        PartCQueryWindow exactMaximum = validator.window(
                "2026-08-29T12:34:56.789Z",
                "2026-09-28T12:34:56.789Z");

        assertThat(exactMaximum.start()).isEqualTo(Instant.parse("2026-08-29T12:34:56.789Z"));
        assertThat(exactMaximum.end()).isEqualTo(NOW);
    }
}
