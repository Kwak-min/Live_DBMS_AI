package com.example.monitoring.lifecycle.integration;

import com.example.monitoring.database.dto.DatabaseCreateRequest;
import com.example.monitoring.database.dto.DatabaseResponse;
import com.example.monitoring.database.dto.DatabaseUpdateRequest;
import com.example.monitoring.database.service.DatabaseConfigService;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.repository.AuditEventRepository;
import com.example.monitoring.repository.DatabaseConfigRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.collector.enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.outbox.retention-cleanup-enabled=false",
        "app.database-security.verify-on-startup=false",
        "app.database-security.allowed-cidrs=127.0.0.1/32",
        "app.database-security.allowed-ports=3306",
        "monitoring.realtime.enabled=false"
})
@ActiveProfiles("local")
class DatabaseLifecycleIntegrationTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_KEYS = "{\"1\":\"" + KEY + "\"}";
    private static final String PREVIOUS_ACTIVE_KEY_VERSION = System.getProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
    private static final String PREVIOUS_ENCRYPTION_KEYS = System.getProperty("DB_CONFIG_ENCRYPTION_KEYS");
    private static final String PREVIOUS_LEGACY_TIME_ZONE = System.getProperty("LEGACY_TIME_ZONE");
    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
            System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", DB_KEYS);
            System.setProperty("LEGACY_TIME_ZONE", "UTC");
            POSTGRES = EmbeddedPostgres.start();
        } catch (Exception exception) {
            restoreProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
            restoreProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
            restoreProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.auth.jwt-signing-keys", () -> "{\"test\":\"" + KEY + "\"}");
        registry.add("app.auth.jwt-active-kid", () -> "test");
        registry.add("app.database-security.encryption-keys", () -> DB_KEYS);
        registry.add("app.database-security.active-key-version", () -> "1");
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
        restoreProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
        restoreProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
        restoreProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
    }

    @Autowired private DatabaseConfigService service;
    @Autowired private DatabaseConfigRepository databaseConfigs;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        dropOutboxRejector();
        deleteRows();
    }

    @AfterEach
    void removeTestObjects() {
        dropOutboxRejector();
        deleteRows();
    }

    @Test
    void productionServiceDrivesAllFiveLifecycleActions() {
        DatabaseResponse created = service.create(createRequest("Created target", true));
        long id = created.id();
        assertResponse(created, id, "Created target", true, 1L);
        assertState(id, 1L, 1L, true, false, "NO_DATA", true);
        String policy = rows("risk_policies", "database_config_id");
        assertDefaultPolicy(id);

        DatabaseUpdateRequest ordinary = update(1L);
        ordinary.setName("Renamed target");
        DatabaseResponse updated = service.update(id, ordinary);
        assertResponse(updated, id, "Renamed target", true, 2L);
        assertState(id, 2L, 2L, true, false, "NO_DATA", true);

        DatabaseUpdateRequest pause = update(2L);
        pause.setEnabled(false);
        DatabaseResponse paused = service.update(id, pause);
        assertResponse(paused, id, "Renamed target", false, 3L);
        assertState(id, 3L, 3L, false, false, "PAUSED", false);

        DatabaseUpdateRequest resume = update(3L);
        resume.setEnabled(true);
        DatabaseResponse resumed = service.update(id, resume);
        assertResponse(resumed, id, "Renamed target", true, 4L);
        assertState(id, 4L, 4L, true, false, "NO_DATA", true);

        service.delete(id);
        entityManager.clear();

        DatabaseConfig deleted = databaseConfigs.findById(id).orElseThrow();
        assertThat(deleted.getName()).isEqualTo("Renamed target");
        assertThat(deleted.getEnabled()).isFalse();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getConfigVersion()).isEqualTo(5L);
        assertState(id, 5L, 5L, false, true, "PAUSED", false);
        assertThat(rows("risk_policies", "database_config_id")).isEqualTo(policy);
        assertThat(auditEvents.count()).isEqualTo(5L);
        assertThat(eventRows()).containsExactly(
                new EventRow(1L, 1L, true, false, "NO_DATA"),
                new EventRow(2L, 2L, true, false, "NO_DATA"),
                new EventRow(3L, 3L, false, false, "PAUSED"),
                new EventRow(4L, 4L, true, false, "NO_DATA"),
                new EventRow(5L, 5L, false, true, "PAUSED"));
    }

    @Test
    void outboxRejectionRollsBackCreateUpdateAndDeleteSnapshots() {
        assertOutboxRejectionRollsBack(() -> service.create(createRequest("Rejected create", true)));

        DatabaseResponse created = service.create(createRequest("Rollback target", true));
        DatabaseUpdateRequest update = update(1L);
        update.setName("Rejected update");
        assertOutboxRejectionRollsBack(() -> service.update(created.id(), update));

        assertOutboxRejectionRollsBack(() -> service.delete(created.id()));
    }

    private void assertOutboxRejectionRollsBack(Runnable mutation) {
        DatabaseSnapshot before = snapshot();
        installOutboxRejector();
        try {
            assertThatThrownBy(mutation::run)
                    .hasStackTraceContaining("forced production lifecycle outbox failure");
        } finally {
            dropOutboxRejector();
        }
        entityManager.clear();
        assertThat(snapshot()).isEqualTo(before);
    }

    private DatabaseCreateRequest createRequest(String name, boolean enabled) {
        return new DatabaseCreateRequest(
                name, "127.0.0.1", 3306, "monitoring", "monitor", "secret", enabled);
    }

    private DatabaseUpdateRequest update(long version) {
        DatabaseUpdateRequest request = new DatabaseUpdateRequest();
        request.setConfigVersion(version);
        return request;
    }

    private void assertResponse(
            DatabaseResponse response,
            long id,
            String name,
            boolean enabled,
            long version
    ) {
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo(name);
        assertThat(response.host()).isEqualTo("127.0.0.1");
        assertThat(response.port()).isEqualTo(3306);
        assertThat(response.databaseName()).isEqualTo("monitoring");
        assertThat(response.enabled()).isEqualTo(enabled);
        assertThat(response.configVersion()).isEqualTo(version);
        assertThat(response.connectionStatus()).isEqualTo(TargetDbStatus.UNKNOWN);
    }

    private void assertState(
            long id,
            long configVersion,
            long stateVersion,
            boolean enabled,
            boolean deleted,
            String freshness,
            boolean activationPresent
    ) {
        var state = jdbc.queryForMap("""
                SELECT config_version, state_version, enabled, deleted, connection_status,
                       data_freshness, risk_level, activation_at, last_attempt_at,
                       last_success_at, latest_metric_id
                FROM monitoring_states
                WHERE database_config_id = ?
                """, id);
        assertThat(((Number) state.get("config_version")).longValue()).isEqualTo(configVersion);
        assertThat(((Number) state.get("state_version")).longValue()).isEqualTo(stateVersion);
        assertThat(state.get("enabled")).isEqualTo(enabled);
        assertThat(state.get("deleted")).isEqualTo(deleted);
        assertThat(state.get("connection_status")).isEqualTo("UNKNOWN");
        assertThat(state.get("data_freshness")).isEqualTo(freshness);
        assertThat(state.get("risk_level")).isNull();
        assertThat(state.get("last_attempt_at")).isNull();
        assertThat(state.get("last_success_at")).isNull();
        assertThat(state.get("latest_metric_id")).isNull();
        if (activationPresent) {
            assertThat(state.get("activation_at")).isNotNull();
        } else {
            assertThat(state.get("activation_at")).isNull();
        }
    }

    private void assertDefaultPolicy(long id) {
        var policy = jdbc.queryForMap("""
                SELECT version, jsonb_array_length(rules) AS rule_count,
                       stale_after_seconds, notification_cooldown_seconds
                FROM risk_policies
                WHERE database_config_id = ?
                """, id);
        assertThat(((Number) policy.get("version")).longValue()).isOne();
        assertThat(((Number) policy.get("rule_count")).intValue()).isEqualTo(2);
        assertThat(((Number) policy.get("stale_after_seconds")).intValue()).isEqualTo(30);
        assertThat(((Number) policy.get("notification_cooldown_seconds")).intValue()).isEqualTo(300);
    }

    private List<EventRow> eventRows() {
        return jdbc.query("""
                SELECT (payload->>'configVersion')::BIGINT AS config_version,
                       (payload->>'stateVersion')::BIGINT AS state_version,
                       (payload->>'enabled')::BOOLEAN AS enabled,
                       (payload->>'deleted')::BOOLEAN AS deleted,
                       payload->>'dataFreshness' AS data_freshness
                FROM event_outbox
                WHERE event_type = 'MonitoringStatusChangedEvent'
                ORDER BY seq
                """, (row, ignored) -> new EventRow(
                row.getLong("config_version"),
                row.getLong("state_version"),
                row.getBoolean("enabled"),
                row.getBoolean("deleted"),
                row.getString("data_freshness")));
    }

    private DatabaseSnapshot snapshot() {
        return new DatabaseSnapshot(
                rows("database_configs", "id"),
                rows("audit_logs", "id"),
                rows("monitoring_states", "database_config_id"),
                rows("risk_policies", "database_config_id"),
                rows("incidents", "incident_id"),
                rows("risk_rule_states", "database_config_id, rule_id"),
                rows("notification_deliveries", "id"),
                rows("event_outbox", "event_id"));
    }

    private String rows(String table, String orderBy) {
        return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(ordered_rows)), '[]'::jsonb)::text "
                + "FROM (SELECT * FROM " + table + " ORDER BY " + orderBy + ") ordered_rows", String.class);
    }

    private void installOutboxRejector() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION integration_reject_outbox_insert()
                RETURNS trigger LANGUAGE plpgsql AS $function$
                BEGIN
                    RAISE EXCEPTION 'forced production lifecycle outbox failure';
                END
                $function$
                """);
        jdbc.execute("""
                CREATE TRIGGER integration_reject_outbox_insert_trigger
                BEFORE INSERT ON event_outbox
                FOR EACH ROW EXECUTE FUNCTION integration_reject_outbox_insert()
                """);
    }

    private void dropOutboxRejector() {
        jdbc.execute("DROP TRIGGER IF EXISTS integration_reject_outbox_insert_trigger ON event_outbox");
        jdbc.execute("DROP FUNCTION IF EXISTS integration_reject_outbox_insert()");
    }

    private void deleteRows() {
        for (String table : List.of(
                "notification_deliveries", "push_subscriptions", "notification_webhooks",
                "risk_rule_states", "incidents", "risk_policies", "monitoring_states",
                "event_outbox", "processed_events", "audit_logs", "metric_data",
                "database_configs")) {
            jdbc.update("DELETE FROM " + table);
        }
        entityManager.clear();
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private record EventRow(
            long configVersion,
            long stateVersion,
            boolean enabled,
            boolean deleted,
            String freshness
    ) {
    }

    private record DatabaseSnapshot(
            String targets,
            String audits,
            String states,
            String policies,
            String incidents,
            String ruleStates,
            String deliveries,
            String outbox
    ) {
    }
}
