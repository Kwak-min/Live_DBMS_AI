package com.example.monitoring.collector;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.MetricData;
import com.example.monitoring.domain.MetricErrorCode;
import com.example.monitoring.domain.MetricUnavailableReason;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.security.TargetConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.sql.SQLException;

class MariaDbMetricsCollectorTest {

    private MariaDbMetricsCollector collector;

    @BeforeEach
    void setUp() throws SQLException {
        TargetConnectionFactory connectionFactory = mock(TargetConnectionFactory.class);
        when(connectionFactory.open(any())).thenThrow(new SQLException("Connection refused"));
        collector = new MariaDbMetricsCollector(connectionFactory);
    }

    @Test
    @DisplayName("Unreachable MariaDB target returns CONNECTION_FAILED status with null metric values (distinction from zero)")
    void unreachableTarget_returnsConnectionFailedWithNullMetrics() {
        CollectorTarget target = new CollectorTarget(
                1L, 1L, "Unreachable-MariaDB", "192.0.2.1", 3306, "test", "root", "password", true);

        MetricData metricData = collector.collectMetrics(target);

        assertNotNull(metricData);
        assertEquals(CollectionStatus.CONNECTION_FAILED, metricData.getCollectionStatus());
        assertNotNull(metricData.getErrorMessage());
        assertEquals(MetricErrorCode.UNKNOWN, metricData.getErrorCode());
        assertEquals(MetricUnavailableReason.COLLECTION_FAILED, metricData.getUnavailableMetrics().get("qps"));

        // Verify key requirement: failed metric collection produces NULL, not numeric 0
        assertNull(metricData.getActiveConnections(), "Active connections must be null on failure");
        assertNull(metricData.getQps(), "QPS must be null on failure");
        assertNull(metricData.getSlowQueries(), "Slow queries must be null on failure");
        assertNull(metricData.getThreadsRunning(), "Threads running must be null on failure");
        assertNull(metricData.getStorageBytes(), "Storage bytes must be null on failure");
    }
}
