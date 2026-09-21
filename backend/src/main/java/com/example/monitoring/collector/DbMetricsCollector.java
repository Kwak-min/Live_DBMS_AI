package com.example.monitoring.collector;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.MetricData;

public interface DbMetricsCollector {
    
    /**
     * Connects to target database, extracts performance metrics, and measures response latency.
     * Guarantees distinction between zero (0 / 0.0) and failure (null).
     *
     * @param config Database connection configuration
     * @return MetricData snapshot with collection status
     */
    MetricData collectMetrics(DatabaseConfig config);
}
