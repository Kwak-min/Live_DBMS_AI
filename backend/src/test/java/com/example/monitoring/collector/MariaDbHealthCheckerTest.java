package com.example.monitoring.collector;

import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.database.security.TargetConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.sql.SQLException;

class MariaDbHealthCheckerTest {

    private MariaDbHealthChecker healthChecker;

    @BeforeEach
    void setUp() throws SQLException {
        TargetConnectionFactory connectionFactory = mock(TargetConnectionFactory.class);
        when(connectionFactory.open(any())).thenThrow(new SQLException("Connection refused"));
        healthChecker = new MariaDbHealthChecker(connectionFactory);
    }

    @Test
    @DisplayName("Unreachable target MariaDB returns DOWN status and error message")
    void unreachableTarget_returnsDownStatus() {
        CollectorTarget config = new CollectorTarget(
                99L, 1L, "TestDB", "192.0.2.1", 3306, "test", "root", "invalid", true);

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
