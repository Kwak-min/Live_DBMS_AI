package com.example.monitoring.collector;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.MetricData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MariaDbMetricsCollectorTest {

    private MariaDbMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MariaDbMetricsCollector();
    }

    @Test
    @DisplayName("Unreachable MariaDB target returns CONNECTION_FAILED status with null metric values (distinction from zero)")
    void unreachableTarget_returnsConnectionFailedWithNullMetrics() {
        DatabaseConfig config = DatabaseConfig.builder()
                .id(1L)
                .name("Unreachable-MariaDB")
                .host("192.0.2.1") // Non-routable IP for instant timeout/failure
                .port(3306)
                .username("root")
                .password("password")
                .databaseName("test")
                .build();

        MetricData metricData = collector.collectMetrics(config);

        assertNotNull(metricData);
        assertEquals(CollectionStatus.CONNECTION_FAILED, metricData.getCollectionStatus());
        assertNotNull(metricData.getErrorMessage());

        // Verify key requirement: failed metric collection produces NULL, not numeric 0
        assertNull(metricData.getActiveConnections(), "Active connections must be null on failure");
        assertNull(metricData.getQps(), "QPS must be null on failure");
        assertNull(metricData.getSlowQueries(), "Slow queries must be null on failure");
        assertNull(metricData.getThreadsRunning(), "Threads running must be null on failure");
        assertNull(metricData.getStorageBytes(), "Storage bytes must be null on failure");
    }
}
