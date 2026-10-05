package com.example.monitoring.retention;

import com.example.monitoring.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
@Import({EmbeddedPostgresSupport.Config.class, PartCRetentionStore.class, PartCRetentionService.class})
@TestPropertySource(properties = {
        "monitoring.retention.batch-size=2",
        "monitoring.retention.max-batches=10"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PartCRetentionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00.000Z");
    private static final Instant DELIVERY_CUTOFF = NOW.minus(30, ChronoUnit.DAYS);
    private static final Instant INCIDENT_CUTOFF = NOW.minus(180, ChronoUnit.DAYS);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PartCRetentionStore store;
    @Autowired private PartCRetentionService service;

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_incident_delete ON incidents");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_incident_delete()");
        jdbc.update("DELETE FROM notification_success_receipts");
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM risk_policies");
        jdbc.update("DELETE FROM monitoring_states");
        jdbc.update("DELETE FROM database_configs");
    }

    @Test
    void deletesOnlyEligibleRowsInBatches() {
        long target = target();
        long webhook = webhook();
        UUID oldWithDelivery = incident(target, "CONNECTION_RATIO", "RESOLVED", INCIDENT_CUTOFF.minusMillis(1));
        UUID atIncidentCutoff = incident(target, "SLOW_QUERY_RATE", "RESOLVED", INCIDENT_CUTOFF);
        UUID recentIncident = incident(target, "CONNECTION_FAILURE", "RESOLVED", INCIDENT_CUTOFF.plusMillis(1));
        UUID ancientOpen = incident(target, "COLLECTION_STALE", "OPEN", INCIDENT_CUTOFF.minus(30, ChronoUnit.DAYS));
        long oldDelivery = delivery(oldWithDelivery, webhook, DELIVERY_CUTOFF.minusMillis(1));
        long boundaryDelivery = delivery(atIncidentCutoff, webhook, DELIVERY_CUTOFF);
        long recentDelivery = delivery(recentIncident, webhook, DELIVERY_CUTOFF.plusMillis(1));

        PartCRetentionService.CleanupResult result = service.purge(NOW);

        assertThat(result.deliveries()).isEqualTo(1);
        assertThat(result.incidents()).isEqualTo(1);
        assertThat(exists("notification_deliveries", "id", oldDelivery)).isFalse();
        assertThat(exists("notification_deliveries", "id", boundaryDelivery)).isTrue();
        assertThat(exists("notification_deliveries", "id", recentDelivery)).isTrue();
        assertThat(exists("incidents", "incident_id", oldWithDelivery)).isFalse();
        assertThat(exists("incidents", "incident_id", atIncidentCutoff)).isTrue();
        assertThat(exists("incidents", "incident_id", recentIncident)).isTrue();
        assertThat(exists("incidents", "incident_id", ancientOpen)).isTrue();
        assertThat(result.batches()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void neverDeletesOpenIncidents() {
        long target = target();
        UUID ancientOpen = incident(target, "CONNECTION_RATIO", "OPEN", INCIDENT_CUTOFF.minus(1000, ChronoUnit.DAYS));

        PartCRetentionService.CleanupResult result = service.purge(NOW);

        assertThat(result.incidents()).isZero();
        assertThat(exists("incidents", "incident_id", ancientOpen)).isTrue();
    }

    @Test
    void receiptsSurviveOpenAndRecentResolvedIncidentsThenCascadeWithOldResolvedParent() {
        long target = target();
        long webhook = webhook();
        UUID ancientOpen = incident(target, "CONNECTION_RATIO", "OPEN",
                INCIDENT_CUTOFF.minus(1000, ChronoUnit.DAYS));
        UUID recentResolved = incident(target, "SLOW_QUERY_RATE", "RESOLVED",
                INCIDENT_CUTOFF.plusMillis(1));
        UUID oldResolved = incident(target, "CONNECTION_FAILURE", "RESOLVED",
                INCIDENT_CUTOFF.minusMillis(1));
        receipt(ancientOpen, webhook, DELIVERY_CUTOFF.minus(1000, ChronoUnit.DAYS));
        receipt(recentResolved, webhook, DELIVERY_CUTOFF.minusSeconds(20));
        receipt(oldResolved, webhook, DELIVERY_CUTOFF.minusSeconds(20));
        delivery(ancientOpen, webhook, DELIVERY_CUTOFF.minusSeconds(20));
        delivery(recentResolved, webhook, DELIVERY_CUTOFF.minusSeconds(20));
        delivery(oldResolved, webhook, DELIVERY_CUTOFF.minusSeconds(20));

        PartCRetentionService.CleanupResult result = service.purge(NOW);

        assertThat(result.deliveries()).isEqualTo(3);
        assertThat(exists("incidents", "incident_id", ancientOpen)).isTrue();
        assertThat(exists("incidents", "incident_id", recentResolved)).isTrue();
        assertThat(exists("incidents", "incident_id", oldResolved)).isFalse();
        assertThat(receiptExists(ancientOpen, webhook)).isTrue();
        assertThat(receiptExists(recentResolved, webhook)).isTrue();
        assertThat(receiptExists(oldResolved, webhook)).isFalse();
    }

    @Test
    void continuesAcrossRestartsAndLeavesConcurrentRecentRows() {
        long target = target();
        long webhook = webhook();
        for (int index = 0; index < 5; index++) {
            UUID incident = incident(target, "CONNECTION_RATIO", "RESOLVED",
                    INCIDENT_CUTOFF.minusSeconds(10 + index));
            delivery(incident, webhook, DELIVERY_CUTOFF.minusSeconds(10 + index));
        }

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        PartCRetentionStore.BatchResult first = transaction.execute(status ->
                store.deleteBatch(DELIVERY_CUTOFF, INCIDENT_CUTOFF, 2));
        UUID recent = incident(target, "SLOW_QUERY_RATE", "RESOLVED", NOW.minus(1, ChronoUnit.DAYS));
        delivery(recent, webhook, NOW.minus(1, ChronoUnit.DAYS));
        int deleted = first.total();
        PartCRetentionStore.BatchResult batch;
        do {
            batch = transaction.execute(status -> store.deleteBatch(DELIVERY_CUTOFF, INCIDENT_CUTOFF, 2));
            assertThat(batch.total()).isLessThanOrEqualTo(2);
            deleted += batch.total();
        } while (batch.total() != 0);

        assertThat(deleted).isEqualTo(10);
        assertThat(exists("incidents", "incident_id", recent)).isTrue();
    }

    @Test
    void rollsBackInjectedFailure() {
        long target = target();
        long webhook = webhook();
        UUID oldIncident = incident(target, "CONNECTION_RATIO", "RESOLVED", INCIDENT_CUTOFF.minusMillis(1));
        long oldDelivery = delivery(oldIncident, webhook, DELIVERY_CUTOFF.minusMillis(1));
        jdbc.execute("""
                CREATE FUNCTION fail_incident_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected retention failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_incident_delete BEFORE DELETE ON incidents
                FOR EACH ROW EXECUTE FUNCTION fail_incident_delete()
                """);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status ->
                store.deleteBatch(DELIVERY_CUTOFF, INCIDENT_CUTOFF, 2)))
                .hasMessageContaining("injected retention failure");

        assertThat(exists("notification_deliveries", "id", oldDelivery)).isTrue();
        assertThat(exists("incidents", "incident_id", oldIncident)).isTrue();
    }

    private long target() {
        return jdbc.queryForObject("""
                INSERT INTO database_configs
                    (collection_interval_seconds, created_at, enabled, host, name, port, status, config_version)
                VALUES (5, now(), true, '127.0.0.1', 'retention-db', 13306, 'UNKNOWN', 1)
                RETURNING id
                """, Long.class);
    }

    private long webhook() {
        return jdbc.queryForObject("""
                INSERT INTO notification_webhooks
                    (name, provider, url_key_version, url_nonce, url_ciphertext, enabled)
                VALUES ('retention', 'SLACK', 1, decode(repeat('00', 12), 'hex'),
                        decode(repeat('11', 17), 'hex'), true)
                RETURNING id
                """, Long.class);
    }

    private UUID incident(long target, String rule, String status, Instant transitionAt) {
        UUID id = UUID.randomUUID();
        boolean open = status.equals("OPEN");
        jdbc.update("""
                INSERT INTO incidents
                    (incident_id, database_config_id, database_name, rule_id, rule_type, severity, status,
                     opened_at, last_observed_at, resolved_at, resolution_reason, metric_name, message,
                     incident_version)
                VALUES (?, ?, 'retention-db', ?, ?, 'CRITICAL', ?, ?, ?, ?, ?, 'metric', 'retention', 1)
                """, id, target, rule, ruleType(rule), status,
                Timestamp.from(transitionAt.minusSeconds(2)), Timestamp.from(transitionAt.minusSeconds(1)),
                open ? null : Timestamp.from(transitionAt), open ? null : "RECOVERED");
        return id;
    }

    private long delivery(UUID incident, long webhook, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notification_deliveries
                    (incident_id, incident_version, notification_type, channel, notification_webhook_id,
                     status, attempt_count, next_attempt_at, expires_at, created_at, sent_at)
                VALUES (?, 1, 'INCIDENT_OPENED', 'SLACK', ?, 'SENT', 1, NULL, ?, ?, ?)
                RETURNING id
                """, Long.class, incident, webhook, Timestamp.from(createdAt.plusSeconds(600)),
                Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(1)));
    }

    private void receipt(UUID incident, long webhook, Instant successfulAt) {
        jdbc.update("""
                INSERT INTO notification_success_receipts
                    (incident_id, channel, notification_webhook_id,
                     last_successful_open_or_increase_at)
                VALUES (?, 'SLACK', ?, ?)
                """, incident, webhook, Timestamp.from(successfulAt));
    }

    private boolean receiptExists(UUID incident, long webhook) {
        return jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM notification_success_receipts
                    WHERE incident_id = ? AND channel = 'SLACK' AND recipient_id = ?
                )
                """, Boolean.class, incident, webhook);
    }

    private boolean exists(String table, String column, Object id) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, id);
        return count != null && count == 1;
    }

    private String ruleType(String rule) {
        return switch (rule) {
            case "CONNECTION_RATIO" -> "CONNECTION_RATIO_EXCEEDED";
            case "SLOW_QUERY_RATE" -> "SLOW_QUERIES_HIGH";
            case "CONNECTION_FAILURE" -> "CONNECTION_FAILURE";
            case "COLLECTION_STALE" -> "COLLECTION_STALE";
            default -> throw new IllegalArgumentException(rule);
        };
    }
}
