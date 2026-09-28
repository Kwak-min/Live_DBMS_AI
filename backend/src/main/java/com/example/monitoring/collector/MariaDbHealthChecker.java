package com.example.monitoring.collector;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.DbPingResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

@Slf4j
@Component
public class MariaDbHealthChecker {

    @Value("${app.collector.connection-timeout-seconds:5}")
    private int connectionTimeoutSeconds;

    public DbPingResponseDto pingAndFetchVersion(DatabaseConfig config) {
        long startTime = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();

        String jdbcUrl = String.format("jdbc:mariadb://%s:%d/%s?connectTimeout=%d&socketTimeout=%d",
                config.getHost(),
                config.getPort(),
                config.getDatabaseName() != null ? config.getDatabaseName() : "",
                connectionTimeoutSeconds * 1000,
                connectionTimeoutSeconds * 1000);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.getUsername(), config.getPassword())) {
            // Lightweight ping query: SELECT 1
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (!rs.next()) {
                    throw new SQLException("Ping query SELECT 1 returned no results.");
                }
            }

            // Version check query: SELECT VERSION()
            String version = null;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
                if (rs.next()) {
                    version = rs.getString(1);
                }
            }

            long latencyMs = System.currentTimeMillis() - startTime;

            return DbPingResponseDto.builder()
                    .databaseConfigId(config.getId())
                    .status(TargetDbStatus.UP)
                    .version(version)
                    .responseTimeMs(latencyMs)
                    .timestamp(now)
                    .errorMessage(null)
                    .build();

        } catch (SQLException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Health check failed for database config ID: {} [{}:{}], error: {}",
                    config.getId(), config.getHost(), config.getPort(), e.getMessage());

            return DbPingResponseDto.builder()
                    .databaseConfigId(config.getId())
                    .status(TargetDbStatus.DOWN)
                    .version(null)
                    .responseTimeMs(durationMs)
                    .timestamp(now)
                    .errorMessage(e.getMessage())
                    .build();
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Unexpected error during database health check for config ID: {}", config.getId(), e);

            return DbPingResponseDto.builder()
                    .databaseConfigId(config.getId())
                    .status(TargetDbStatus.DOWN)
                    .version(null)
                    .responseTimeMs(durationMs)
                    .timestamp(now)
                    .errorMessage("Unexpected error: " + e.getMessage())
                    .build();
        }
    }
}
