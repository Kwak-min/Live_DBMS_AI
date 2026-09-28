package com.example.monitoring.collector;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.MetricData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MariaDbMetricsCollector implements DbMetricsCollector {

    @Value("${app.collector.connection-timeout-seconds:5}")
    private int connectionTimeoutSeconds;

    // Memory cache for calculating differential QPS (dbId -> LastQueryState)
    private final Map<Long, QueryState> lastQueryStates = new ConcurrentHashMap<>();

    private static class QueryState {
        final long totalQueries;
        final long timestampMs;

        QueryState(long totalQueries, long timestampMs) {
            this.totalQueries = totalQueries;
            this.timestampMs = timestampMs;
        }
    }

    @Override
    public MetricData collectMetrics(DatabaseConfig config) {
        long startTime = System.currentTimeMillis();
        LocalDateTime collectionTimestamp = LocalDateTime.now();

        String jdbcUrl = String.format("jdbc:mariadb://%s:%d/%s?connectTimeout=%d&socketTimeout=%d",
                config.getHost(),
                config.getPort(),
                config.getDatabaseName() != null ? config.getDatabaseName() : "",
                connectionTimeoutSeconds * 1000,
                connectionTimeoutSeconds * 1000);

        MetricData metricData = MetricData.builder()
                .databaseConfig(config)
                .timestamp(collectionTimestamp)
                .build();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getUsername(), config.getPassword())) {
            long pingMs = System.currentTimeMillis() - startTime;
            metricData.setResponseTimeMs(pingMs);

            Map<String, String> statusVars = fetchGlobalStatus(conn);
            Map<String, String> systemVars = fetchGlobalVariables(conn);
            Long storageBytes = fetchStorageBytes(conn);

            // Active Connections
            String threadsConnected = statusVars.get("Threads_connected");
            metricData.setActiveConnections(threadsConnected != null ? Long.parseLong(threadsConnected) : null);

            // Threads Running
            String threadsRunning = statusVars.get("Threads_running");
            metricData.setThreadsRunning(threadsRunning != null ? Long.parseLong(threadsRunning) : null);

            // Slow Queries
            String slowQueries = statusVars.get("Slow_queries");
            metricData.setSlowQueries(slowQueries != null ? Long.parseLong(slowQueries) : null);

            // Max Connections
            String maxConn = systemVars.get("max_connections");
            metricData.setMaxConnections(maxConn != null ? Long.parseLong(maxConn) : null);

            // Storage Bytes
            metricData.setStorageBytes(storageBytes);

            // Calculate QPS
            String totalQueriesStr = statusVars.get("Queries");
            if (totalQueriesStr != null) {
                long currentQueries = Long.parseLong(totalQueriesStr);
                long currentMs = System.currentTimeMillis();
                QueryState previousState = lastQueryStates.put(config.getId(), new QueryState(currentQueries, currentMs));

                if (previousState != null && currentMs > previousState.timestampMs) {
                    double secondsElapsed = (currentMs - previousState.timestampMs) / 1000.0;
                    long queriesDiff = currentQueries - previousState.totalQueries;
                    if (queriesDiff >= 0 && secondsElapsed > 0) {
                        metricData.setQps(queriesDiff / secondsElapsed);
                    } else {
                        metricData.setQps(0.0);
                    }
                } else {
                    metricData.setQps(0.0);
                }
            } else {
                metricData.setQps(null);
            }

            // Optional OS/DB level CPU and Memory metrics (0.0 or null if inaccessible without remote agent)
            // If DB engine reports process CPU or memory status via plugin/INNODB status, populate here.
            // Explicitly distinct 0.0 (idle) from null (unsupported/failed).
            metricData.setCpuUsage(null);
            metricData.setMemoryUsage(null);

            metricData.setCollectionStatus(CollectionStatus.SUCCESS);
            metricData.setErrorMessage(null);

        } catch (SQLException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Failed to collect metrics from MariaDB target [{}:{}] - Error: {}",
                    config.getHost(), config.getPort(), e.getMessage());

            metricData.setResponseTimeMs(durationMs);
            metricData.setCollectionStatus(CollectionStatus.CONNECTION_FAILED);
            metricData.setErrorMessage(e.getMessage());
            
            // Critical rule: Set metrics to null (NOT 0!) on collection failure
            metricData.setActiveConnections(null);
            metricData.setMaxConnections(null);
            metricData.setQps(null);
            metricData.setSlowQueries(null);
            metricData.setThreadsRunning(null);
            metricData.setStorageBytes(null);
            metricData.setCpuUsage(null);
            metricData.setMemoryUsage(null);
        } catch (Exception e) {
            log.error("Unexpected error during metric collection for dbId: {}", config.getId(), e);
            metricData.setCollectionStatus(CollectionStatus.PARTIAL_FAILURE);
            metricData.setErrorMessage("Unexpected failure: " + e.getMessage());
        }

        return metricData;
    }

    private Map<String, String> fetchGlobalStatus(Connection conn) throws SQLException {
        Map<String, String> map = new HashMap<>();
        String sql = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected', 'Threads_running', 'Queries', 'Slow_queries', 'Uptime')";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getString("Variable_name"), rs.getString("Value"));
            }
        }
        return map;
    }

    private Map<String, String> fetchGlobalVariables(Connection conn) throws SQLException {
        Map<String, String> map = new HashMap<>();
        String sql = "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('max_connections')";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getString("Variable_name"), rs.getString("Value"));
            }
        }
        return map;
    }

    private Long fetchStorageBytes(Connection conn) {
        String sql = "SELECT SUM(data_length + index_length) FROM information_schema.TABLES";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                long val = rs.getLong(1);
                return rs.wasNull() ? null : val;
            }
        } catch (SQLException e) {
            log.debug("Could not fetch table storage bytes: {}", e.getMessage());
        }
        return null;
    }
}
