package com.example.monitoring.risk.contract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractUnicodeCodePointTest {

    @Test
    void incidentNameLimitCountsUnicodeCodePoints() {
        String sixtyEmoji = "🐘".repeat(60);

        Incident incident = incident(sixtyEmoji);

        assertThat(incident.databaseName()).isEqualTo(sixtyEmoji);
        assertThat(incident.databaseName().codePointCount(0, incident.databaseName().length()))
                .isEqualTo(60);
    }

    @Test
    void incidentNameStillRejectsMoreThanOneHundredCodePoints() {
        assertThatThrownBy(() -> incident("🐘".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("databaseName length");
    }

    private Incident incident(String databaseName) {
        Instant at = Instant.parse("2026-10-02T03:04:05Z");
        return new Incident(
                UUID.fromString("00000000-0000-0000-0000-000000000222"),
                12L,
                databaseName,
                RuleId.CONNECTION_RATIO,
                RuleType.CONNECTION_RATIO_EXCEEDED,
                IncidentSeverity.WARNING,
                IncidentStatus.OPEN,
                at,
                at,
                null,
                null,
                "activeConnectionsRatio",
                new BigDecimal("0.81"),
                new BigDecimal("0.80"),
                501L,
                "Connection ratio warning",
                1L);
    }
}
