package com.example.monitoring.lifecycle.schema;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PartCStagedSchemaTest {

    private static final Path REPOSITORY = locateRepository();
    private static final Path SCHEMA_ROOT = REPOSITORY.resolve("backend/schema/part-c");
    private static final String DB_KEYS = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PREVIOUS_ACTIVE_KEY_VERSION = System.getProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
    private static final String PREVIOUS_ENCRYPTION_KEYS = System.getProperty("DB_CONFIG_ENCRYPTION_KEYS");
    private static final String PREVIOUS_LEGACY_TIME_ZONE = System.getProperty("LEGACY_TIME_ZONE");
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
        System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", "{\"1\":\"" + DB_KEYS + "\"}");
        System.setProperty("LEGACY_TIME_ZONE", "UTC");
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
        restoreProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
        restoreProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
        restoreProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
    }

    @Test
    void stagedV4FailsWithoutAuthSessionOwnerKey() throws Exception {
        ActualDatabase database = prepareActualDatabase();
        try {
            seedActualPrerequisites(database.dataSource());
            addActualCompositeKeys(database.dataSource(), false, true);
            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, () -> applyStagedV4(database.dataSource()));

            assertThat(failure.getSQLState()).isEqualTo("42830");
        } finally {
            dropActualDatabase(database);
        }
    }

    @Test
    void stagedV4FailsWithoutMetricTargetKey() throws Exception {
        ActualDatabase database = prepareActualDatabase();
        try {
            seedActualPrerequisites(database.dataSource());
            addActualCompositeKeys(database.dataSource(), true, false);
            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, () -> applyStagedV4(database.dataSource()));

            assertThat(failure.getSQLState()).isEqualTo("42830");
        } finally {
            dropActualDatabase(database);
        }
    }

    @Test
    void preV4ProbeFailsBeforeMonitoringTablesExistOnActualV3() throws Exception {
        ActualDatabase database = prepareActualDatabase();
        try {
            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class,
                    () -> executeScript(database.dataSource(), "probes/pre-v4-missing-schema.sql", "public"));

            assertThat(failure.getSQLState()).isEqualTo("42P01");
        } finally {
            dropActualDatabase(database);
        }
    }

    @Test
    void isolatedFixtureV4AndConstraintProbePassWithDisposablePrerequisites() throws Exception {
        prepareFixture();
        try {
            applyStagedV4(dataSource, "part_c_probe");
            executeScript("probes/catalog.sql");
            assertMonitoringStateActivationColumn(dataSource, "part_c_probe");
            assertPartCTableInventory(dataSource, "part_c_probe");
            assertOutboxShape(dataSource, "part_c_probe");
            ProbeResult result = runConstraintProbe(dataSource, "part_c_probe");

            assertThat(result.passedScenarios()).isEqualTo(21);
            assertThat(result.scenarios()).contains(
                    "activation_at_nullable_timestamptz",
                    "event_outbox_actual_eleven_columns",
                    "disabled_activation_coherent",
                    "deleted_state_activation_coherent",
                    "enabled_activation_required",
                    "disabled_activation_must_be_null");
        } finally {
            cleanupProbe();
        }
    }

    @Test
    void actualV3AndStagedV4PassWithOnlyMissingCompositeKeysAdded() throws Exception {
        ActualDatabase database = prepareActualDatabase();
        try {
            seedActualPrerequisites(database.dataSource());
            assertActualAContract(database.dataSource());
            addActualCompositeKeys(database.dataSource(), true, true);
            applyStagedV4(database.dataSource());
            executeScript(database.dataSource(), "probes/catalog.sql", "public");
            assertMonitoringStateActivationColumn(database.dataSource(), "public");
            assertPartCTableInventory(database.dataSource(), "public");
            assertOutboxShape(database.dataSource(), "public");
            ProbeResult result = runConstraintProbe(database.dataSource(), "public");

            assertThat(result.passedScenarios()).isEqualTo(21);
            assertThat(result.scenarios()).contains(
                    "activation_at_nullable_timestamptz",
                    "event_outbox_actual_eleven_columns",
                    "disabled_activation_coherent",
                    "deleted_state_activation_coherent",
                    "enabled_activation_required",
                    "disabled_activation_must_be_null");
        } finally {
            dropActualDatabase(database);
        }
    }

    @Test
    void activeMigrationInventoryKeepsActualV1V2V3Only() throws IOException {
        List<String> names = new ArrayList<>();
        collectMigrationNames(names, REPOSITORY.resolve("backend/src/main/resources/db/migration"));
        collectMigrationNames(names, REPOSITORY.resolve("backend/src/main/java/db/migration"));
        names.sort(String::compareTo);

        assertThat(names).containsExactly(
                "V1__baseline_existing_schema.sql",
                "V2__part_b_auth_and_encrypt_database_credentials.java",
                "V3__part_a_metrics_and_outbox.java");
        assertThat(Files.exists(SCHEMA_ROOT.resolve("V4__part_c_monitoring.sql"))).isTrue();
    }

    private ActualDatabase prepareActualDatabase() throws Exception {
        String name = "part_c_actual_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(name));
        }

        DataSource actual = postgres.getDatabase("postgres", name);
        Flyway.configure().dataSource(actual).load().migrate();
        return new ActualDatabase(name, actual);
    }

    private void seedActualPrerequisites(DataSource source) throws Exception {
        withConnection(source, connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO users (
                            id, email, display_name, password_hash, role, enabled,
                            auth_version, created_at, updated_at
                        ) VALUES
                            (201, 'one@example.test', 'One', 'hash-one', 'USER', TRUE, 1,
                             '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z'),
                            (202, 'two@example.test', 'Two', 'hash-two', 'USER', TRUE, 1,
                             '2026-09-29T00:00:00Z', '2026-09-29T00:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO auth_sessions (
                            sid, user_id, current_refresh_hash, created_at, expires_at, auth_version
                        ) VALUES
                            ('00000000-0000-0000-0000-000000000201', 201, 'refresh-one',
                             '2026-09-29T00:00:00Z', '2026-10-06T00:00:00Z', 1),
                            ('00000000-0000-0000-0000-000000000202', 202, 'refresh-two',
                             '2026-09-29T00:00:00Z', '2026-10-06T00:00:00Z', 1)
                        """);
                statement.execute("""
                        INSERT INTO database_configs (
                            id, collection_interval_seconds, created_at, enabled, host,
                            name, port, status, updated_at, config_version
                        ) VALUES
                            (101, 60, '2026-09-29T00:00:00Z', TRUE, '127.0.0.1',
                             'Primary database', 5432, 'UP', '2026-09-29T00:00:00Z', 1),
                            (102, 60, '2026-09-29T00:00:00Z', TRUE, '127.0.0.1',
                             'Replica database', 5433, 'UP', '2026-09-29T00:00:00Z', 1)
                        """);
                statement.execute("""
                        INSERT INTO metric_data (
                            id, collection_status, created_at, database_config_id, timestamp,
                            config_version, collection_attempt_time, unavailable_metrics
                        ) VALUES
                            (301, 'SUCCESS', '2026-09-29T00:00:00Z', 101,
                             '2026-09-29T00:00:00Z', 1, '2026-09-29T00:00:00Z', '{}'::jsonb),
                            (302, 'SUCCESS', '2026-09-29T00:00:00Z', 102,
                             '2026-09-29T00:00:00Z', 1, '2026-09-29T00:00:00Z', '{}'::jsonb)
                        """);
            }
            connection.commit();
            return null;
        });
    }

    private void assertActualAContract(DataSource source) throws Exception {
        withConnection(source, connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT
                             (SELECT count(*) FROM information_schema.columns
                              WHERE table_schema = 'public' AND table_name = 'event_outbox') AS outbox_columns,
                             (SELECT count(*) FROM information_schema.columns
                              WHERE table_schema = 'public' AND table_name = 'processed_events') AS processed_columns
                         """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("outbox_columns")).isEqualTo(11);
                assertThat(rows.getInt("processed_columns")).isEqualTo(4);
                return null;
            }
        });
    }

    private void addActualCompositeKeys(DataSource source, boolean authKey, boolean metricKey) throws Exception {
        withConnection(source, connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (authKey) {
                    statement.execute("ALTER TABLE auth_sessions ADD CONSTRAINT auth_sessions_sid_user_unique UNIQUE (sid, user_id)");
                }
                if (metricKey) {
                    statement.execute("ALTER TABLE metric_data ADD CONSTRAINT metric_data_id_target_unique UNIQUE (id, database_config_id)");
                }
            }
            connection.commit();
            return null;
        });
    }

    private void dropActualDatabase(ActualDatabase database) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + quoteIdentifier(database.name()));
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void prepareFixture() throws Exception {
        executeScript("test-fixtures/V2_V3_prerequisites.sql");
    }

    private void applyStagedV4() throws Exception {
        applyStagedV4(dataSource, "part_c_probe");
    }

    private void applyStagedV4(DataSource source) throws Exception {
        applyStagedV4(source, "public");
    }

    private void applyStagedV4(DataSource source, String schema) throws Exception {
        withConnection(source, connection -> {
            connection.setAutoCommit(false);
            setSearchPath(connection, schema);
            executeScript(connection, SCHEMA_ROOT.resolve("V4__part_c_monitoring.sql"));
            connection.commit();
            return null;
        });
    }

    private ProbeResult runConstraintProbe() throws Exception {
        return runConstraintProbe(dataSource, "part_c_probe");
    }

    private ProbeResult runConstraintProbe(DataSource source, String schema) throws Exception {
        return withConnection(source, connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve("probes/constraints.sql"), schema);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT count(*), string_agg(scenario, ',' ORDER BY scenario) "
                                 + "FROM part_c_probe_results")) {
                rows.next();
                ProbeResult result = new ProbeResult(rows.getInt(1), rows.getString(2));
                connection.rollback();
                return result;
            }
        });
    }

    private void assertMonitoringStateActivationColumn() throws Exception {
        assertMonitoringStateActivationColumn(dataSource, "part_c_probe");
    }

    private void assertMonitoringStateActivationColumn(DataSource source, String schema) throws Exception {
        Column column = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = '%s'
                           AND table_name = 'monitoring_states'
                           AND column_name = 'activation_at'
                         """.formatted(schema))) {
                assertThat(rows.next()).isTrue();
                Column result = new Column(rows.getString("column_name"),
                        rows.getString("data_type"), rows.getString("udt_name"),
                        rows.getString("is_nullable"));
                connection.rollback();
                return result;
            }
        });

        assertThat(column.name()).isEqualTo("activation_at");
        assertThat(column.dataType()).isEqualTo("timestamp with time zone");
        assertThat(column.udtName()).isEqualTo("timestamptz");
        assertThat(column.nullable()).isEqualTo("YES");
    }

    private void assertPartCTableInventory() throws Exception {
        assertPartCTableInventory(dataSource, "part_c_probe");
    }

    private void assertPartCTableInventory(DataSource source, String schema) throws Exception {
        List<String> tables = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            List<String> names = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT table_name
                         FROM information_schema.tables
                         WHERE table_schema = '%s'
                           AND table_type = 'BASE TABLE'
                           AND table_name NOT IN (
                               'users', 'auth_sessions', 'used_refresh_tokens',
                               'database_configs', 'metric_data', 'blocked_reasons',
                               'audit_logs', 'access_logs', 'event_outbox', 'processed_events',
                               'flyway_schema_history'
                           )
                         ORDER BY table_name
                         """.formatted(schema))) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
                connection.rollback();
                return names;
            }
        });

        assertThat(tables).containsExactly(
                "incidents", "monitoring_states", "notification_deliveries",
                "notification_webhooks", "push_subscriptions", "risk_policies",
                "risk_rule_states");
    }

    private void assertOutboxShape() throws Exception {
        assertOutboxShape(dataSource, "part_c_probe");
    }

    private void assertOutboxShape(DataSource source, String schema) throws Exception {
        List<Column> columns = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            List<Column> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = '%s'
                           AND table_name = 'event_outbox'
                         ORDER BY ordinal_position
                         """.formatted(schema))) {
                while (rows.next()) {
                    result.add(new Column(rows.getString("column_name"),
                            rows.getString("data_type"), rows.getString("udt_name"),
                            rows.getString("is_nullable")));
                }
                connection.rollback();
                return result;
            }
        });

        assertThat(columns).extracting(Column::name).containsExactly(
                "event_id", "seq", "event_type", "stream_key", "ordering_key", "payload",
                "created_at", "published_at", "attempts", "next_attempt_at", "last_error");
        assertThat(columns.get(5).udtName()).isEqualTo("jsonb");
        assertThat(columns.get(6).udtName()).isEqualTo("timestamptz");
        assertThat(columns.get(7).udtName()).isEqualTo("timestamptz");
        assertThat(columns.get(9).udtName()).isEqualTo("timestamptz");

        List<Column> processed = withConnection(source, connection -> {
            connection.setAutoCommit(false);
            List<Column> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = '%s'
                           AND table_name = 'processed_events'
                         ORDER BY ordinal_position
                         """.formatted(schema))) {
                while (rows.next()) {
                    result.add(new Column(rows.getString("column_name"),
                            rows.getString("data_type"), rows.getString("udt_name"),
                            rows.getString("is_nullable")));
                }
                connection.rollback();
                return result;
            }
        });
        assertThat(processed).extracting(Column::name).containsExactly(
                "stream", "consumer_group", "event_id", "processed_at");
        assertThat(processed.get(3).udtName()).isEqualTo("timestamptz");
    }

    private void executeScript(String relativePath) throws Exception {
        withConnection(connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve(relativePath));
            connection.commit();
            return null;
        });
    }

    private void executeScript(DataSource source, String relativePath, String schema) throws Exception {
        withConnection(source, connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve(relativePath), schema);
            connection.commit();
            return null;
        });
    }

    private void execute(String sql) throws Exception {
        withConnection(connection -> {
            connection.setAutoCommit(false);
            setProbeSearchPath(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
            return null;
        });
    }

    private void cleanupProbe() throws Exception {
        withConnection(connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve("probes/cleanup.sql"));
            connection.commit();
            return null;
        });

        boolean removed = withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT to_regnamespace('part_c_probe') IS NULL")) {
                rows.next();
                return rows.getBoolean(1);
            }
        });
        assertThat(removed).isTrue();
    }

    private static void setProbeSearchPath(Connection connection) throws SQLException {
        setSearchPath(connection, "part_c_probe");
    }

    private static void setSearchPath(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + quoteIdentifier(schema) + ", pg_catalog");
        }
    }

    private static void collectMigrationNames(List<String> names, Path migrations) throws IOException {
        try (var paths = Files.list(migrations)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        }
    }

    private static void executeScript(Connection connection, Path path) throws Exception {
        executeScript(connection, path, "part_c_probe");
    }

    private static void executeScript(Connection connection, Path path, String schema) throws Exception {
        String script = Files.readString(path, StandardCharsets.UTF_8);
        if (!schema.equals("part_c_probe")) {
            script = script.replace("part_c_probe, pg_catalog", schema + ", pg_catalog")
                    .replace("'part_c_probe'", "'" + schema + "'");
        }
        for (String statement : splitSql(script)) {
            try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }
    }

    private static List<String> splitSql(String script) {
        List<String> statements = new ArrayList<>();
        int start = 0;
        char quote = 0;
        String dollarQuote = null;
        boolean lineComment = false;
        for (int index = 0; index < script.length(); index++) {
            char current = script.charAt(index);
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (dollarQuote != null) {
                if (script.startsWith(dollarQuote, index)) {
                    index += dollarQuote.length() - 1;
                    dollarQuote = null;
                }
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    if (index + 1 < script.length() && script.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '-' && index + 1 < script.length() && script.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '$') {
                int end = script.indexOf('$', index + 1);
                if (end >= 0 && script.substring(index + 1, end)
                        .matches("[A-Za-z_][A-Za-z0-9_]*|")) {
                    dollarQuote = script.substring(index, end + 1);
                    index = end;
                }
            } else if (current == ';') {
                addStatement(statements, script.substring(start, index));
                start = index + 1;
            }
        }
        addStatement(statements, script.substring(start));
        return statements;
    }

    private static void addStatement(List<String> statements, String statement) {
        String trimmed = statement.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
            statements.add(trimmed);
        }
    }

    private static Path locateRepository() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend/schema/part-c"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }

    private <T> T withConnection(SqlWork<T> work) throws Exception {
        return withConnection(dataSource, work);
    }

    private <T> T withConnection(DataSource source, SqlWork<T> work) throws Exception {
        try (Connection connection = source.getConnection()) {
            return work.apply(connection);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws Exception;
    }

    private record Column(String name, String dataType, String udtName, String nullable) { }

    private record ProbeResult(int passedScenarios, String scenarios) { }

    private record ActualDatabase(String name, DataSource dataSource) { }
}
