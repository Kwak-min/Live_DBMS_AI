package com.example.monitoring.lifecycle.schema;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartCStagedSchemaTest {

    private static final Path REPOSITORY = locateRepository();
    private static final Path SCHEMA_ROOT = REPOSITORY.resolve("backend/schema/part-c");
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void stagedV4FailsWithoutAuthSessionOwnerKey() throws Exception {
        prepareFixture();
        try {
            execute("ALTER TABLE auth_sessions DROP CONSTRAINT auth_sessions_sid_user_unique");
            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, this::applyStagedV4);

            assertThat(failure.getSQLState()).isEqualTo("42830");
        } finally {
            cleanupProbe();
        }
    }

    @Test
    void preV4ProbeFailsBeforeMonitoringTablesExist() throws Exception {
        prepareFixture();
        try {
            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, () -> executeScript("probes/pre-v4-missing-schema.sql"));

            assertThat(failure.getSQLState()).isEqualTo("42P01");
        } finally {
            cleanupProbe();
        }
    }

    @Test
    void stagedV4AndConstraintProbePassWithDisposablePrerequisites() throws Exception {
        prepareFixture();
        try {
            applyStagedV4();
            executeScript("probes/catalog.sql");
            assertMonitoringStateActivationColumn();
            assertPartCTableInventory();
            assertOutboxShape();
            ProbeResult result = runConstraintProbe();

            assertThat(result.passedScenarios()).isEqualTo(21);
            assertThat(result.scenarios()).contains(
                    "activation_at_nullable_timestamptz",
                    "event_outbox_exact_seven_columns",
                    "disabled_activation_coherent",
                    "deleted_state_activation_coherent",
                    "enabled_activation_required",
                    "disabled_activation_must_be_null");
        } finally {
            cleanupProbe();
        }
    }

    @Test
    void activeMigrationInventoryKeepsV1AndV2Only() throws IOException {
        List<String> names = new ArrayList<>();
        collectMigrationNames(names, REPOSITORY.resolve("backend/src/main/resources/db/migration"));
        collectMigrationNames(names, REPOSITORY.resolve("backend/src/main/java/db/migration"));
        names.sort(String::compareTo);

        assertThat(names).containsExactly(
                "V1__baseline_existing_schema.sql",
                "V2__part_b_auth_and_encrypt_database_credentials.java");
        assertThat(Files.exists(SCHEMA_ROOT.resolve("V4__part_c_monitoring.sql"))).isTrue();
    }

    private void prepareFixture() throws Exception {
        executeScript("test-fixtures/V2_V3_prerequisites.sql");
    }

    private void applyStagedV4() throws Exception {
        withConnection(connection -> {
            connection.setAutoCommit(false);
            setProbeSearchPath(connection);
            executeScript(connection, SCHEMA_ROOT.resolve("V4__part_c_monitoring.sql"));
            connection.commit();
            return null;
        });
    }

    private ProbeResult runConstraintProbe() throws Exception {
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve("probes/constraints.sql"));
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
        Column column = withConnection(connection -> {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = 'part_c_probe'
                           AND table_name = 'monitoring_states'
                           AND column_name = 'activation_at'
                         """)) {
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
        List<String> tables = withConnection(connection -> {
            connection.setAutoCommit(false);
            List<String> names = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT table_name
                         FROM information_schema.tables
                         WHERE table_schema = 'part_c_probe'
                           AND table_type = 'BASE TABLE'
                           AND table_name NOT IN (
                               'users', 'auth_sessions', 'database_configs',
                               'metric_data', 'event_outbox'
                           )
                         ORDER BY table_name
                         """)) {
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
        List<Column> columns = withConnection(connection -> {
            connection.setAutoCommit(false);
            List<Column> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT column_name, data_type, udt_name, is_nullable
                         FROM information_schema.columns
                         WHERE table_schema = 'part_c_probe'
                           AND table_name = 'event_outbox'
                         ORDER BY ordinal_position
                         """)) {
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
                "event_id", "event_type", "payload", "created_at", "published_at",
                "attempts", "next_attempt_at");
        assertThat(columns.get(2).udtName()).isEqualTo("jsonb");
        assertThat(columns.get(3).udtName()).isEqualTo("timestamptz");
        assertThat(columns.get(4).udtName()).isEqualTo("timestamptz");
        assertThat(columns.get(6).udtName()).isEqualTo("timestamptz");
    }

    private void executeScript(String relativePath) throws Exception {
        withConnection(connection -> {
            connection.setAutoCommit(false);
            executeScript(connection, SCHEMA_ROOT.resolve(relativePath));
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
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO part_c_probe, pg_catalog");
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
        for (String statement : splitSql(Files.readString(path, StandardCharsets.UTF_8))) {
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
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws Exception;
    }

    private record Column(String name, String dataType, String udtName, String nullable) { }

    private record ProbeResult(int passedScenarios, String scenarios) { }
}
