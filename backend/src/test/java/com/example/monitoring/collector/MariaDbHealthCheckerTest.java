package com.example.monitoring.collector;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.DbPingResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MariaDbHealthCheckerTest {

    private MariaDbHealthChecker healthChecker;

    @BeforeEach
    void setUp() {
        healthChecker = new MariaDbHealthChecker();
    }

    @Test
    @DisplayName("Unreachable target MariaDB returns DOWN status and error message")
    void unreachableTarget_returnsDownStatus() {
        DatabaseConfig config = DatabaseConfig.builder()
                .id(99L)
                .name("TestDB")
                .host("192.0.2.1") // Non-routable IP for failure
                .port(3306)
                .username("root")
                .password("invalid")
                .databaseName("test")
                .build();

        DbPingResponseDto result = healthChecker.pingAndFetchVersion(config);

        assertNotNull(result);
        assertEquals(99L, result.getDatabaseConfigId());
        assertEquals(TargetDbStatus.DOWN, result.getStatus());
        assertNull(result.getVersion());
        assertNotNull(result.getErrorMessage());
        assertNotNull(result.getResponseTimeMs());
        assertNotNull(result.getTimestamp());
    }
}
