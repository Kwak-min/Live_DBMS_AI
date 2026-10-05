package com.example.monitoring.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

@Repository
public class PartCRetentionStore {

    private final JdbcTemplate jdbc;

    public PartCRetentionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BatchResult deleteBatch(Instant deliveryCutoff, Instant incidentCutoff, int batchSize) {
        Objects.requireNonNull(deliveryCutoff, "deliveryCutoff");
        Objects.requireNonNull(incidentCutoff, "incidentCutoff");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        int deliveries = jdbc.update("""
                WITH locked_incidents AS MATERIALIZED (
                    SELECT incident.incident_id
                    FROM incidents AS incident
                    WHERE EXISTS (
                        SELECT 1
                        FROM notification_deliveries AS delivery
                        WHERE delivery.incident_id = incident.incident_id
                          AND delivery.created_at < ?
                    )
                    ORDER BY incident.incident_id
                    LIMIT ?
                    FOR UPDATE OF incident SKIP LOCKED
                ), candidates AS (
                    SELECT delivery.id
                    FROM notification_deliveries AS delivery
                    JOIN locked_incidents AS incident
                      ON incident.incident_id = delivery.incident_id
                    WHERE delivery.created_at < ?
                    ORDER BY delivery.id
                    LIMIT ?
                    FOR UPDATE OF delivery SKIP LOCKED
                )
                DELETE FROM notification_deliveries AS delivery
                USING candidates
                WHERE delivery.id = candidates.id
                """, Timestamp.from(deliveryCutoff), batchSize,
                Timestamp.from(deliveryCutoff), batchSize);

        int remaining = batchSize - deliveries;
        int incidents = remaining == 0 ? 0 : jdbc.update("""
                WITH candidates AS (
                    SELECT incident.incident_id
                    FROM incidents AS incident
                    WHERE incident.status = 'RESOLVED'
                      AND incident.resolved_at < ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM notification_deliveries AS delivery
                          WHERE delivery.incident_id = incident.incident_id
                      )
                    ORDER BY incident.incident_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM incidents AS incident
                USING candidates
                WHERE incident.incident_id = candidates.incident_id
                """, Timestamp.from(incidentCutoff), remaining);
        return new BatchResult(deliveries, incidents);
    }

    public record BatchResult(int deliveries, int incidents) {
        public int total() {
            return deliveries + incidents;
        }
    }
}
