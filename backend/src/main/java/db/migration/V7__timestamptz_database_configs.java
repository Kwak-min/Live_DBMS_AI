package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/**
 * V7: 마지막으로 남은 TIMESTAMP WITHOUT TIME ZONE 컬럼을 timestamptz로 바꾼다.
 * <ul>
 *   <li>database_configs: created_at, updated_at, last_checked_at, last_success_at, deleted_at</li>
 *   <li>blocked_reasons(레거시 차단 이력): blocked_at, unblocked_at</li>
 * </ul>
 * 이 값들은 지금까지 JVM 기본 시간대의 LocalDateTime으로 기록됐다. V3(metric_data)와 같은 규칙으로,
 * 두 테이블 중 하나라도 행이 있으면 LEGACY_TIME_ZONE(기록 당시 JVM 시간대)이 필수이며 추측하지 않는다.
 * 빈 DB(신규 설치·테스트)는 UTC로 변환한다(변환할 값이 없다).
 */
public class V7__timestamptz_database_configs extends BaseJavaMigration {

    private static final List<String> DATABASE_CONFIG_COLUMNS =
            List.of("created_at", "updated_at", "last_checked_at", "last_success_at", "deleted_at");
    private static final List<String> BLOCKED_REASON_COLUMNS = List.of("blocked_at", "unblocked_at");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean hasRows = hasRows(connection, "database_configs") || hasRows(connection, "blocked_reasons");
        String zone = hasRows ? legacyTimeZone() : "UTC";
        for (String column : DATABASE_CONFIG_COLUMNS) {
            convert(connection, "database_configs", column, zone);
        }
        for (String column : BLOCKED_REASON_COLUMNS) {
            convert(connection, "blocked_reasons", column, zone);
        }
    }

    private void convert(Connection connection, String table, String column, String zone) throws SQLException {
        execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE TIMESTAMPTZ "
                + "USING " + column + " AT TIME ZONE '" + zone + "'");
    }

    private String legacyTimeZone() {
        String value = System.getenv("LEGACY_TIME_ZONE");
        if (value == null || value.isBlank()) value = System.getProperty("LEGACY_TIME_ZONE");
        if (value == null || value.isBlank()) {
            throw new FlywayException("LEGACY_TIME_ZONE is required for V7 because database_configs or "
                    + "blocked_reasons has legacy rows.");
        }
        try {
            return ZoneId.of(value).getId().replace("'", "''");
        } catch (DateTimeException exception) {
            throw new FlywayException("LEGACY_TIME_ZONE is not a valid time zone.", exception);
        }
    }

    private boolean hasRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM " + table + ")")) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
