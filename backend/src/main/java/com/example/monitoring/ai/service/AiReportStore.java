package com.example.monitoring.ai.service;

import com.example.monitoring.ai.model.AiReportStatus;
import com.example.monitoring.ai.model.AiReportType;
import com.example.monitoring.ai.model.AiTriggerSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** ai_reports JDBC 저장소. 중복 PENDING은 부분 unique index가 막고 호출자는 DuplicateKeyException을 받는다. */
@Repository
public class AiReportStore {

    private static final String COLUMNS = """
            id, report_type, database_config_id, database_name, report_date, window_start, window_end, status,
            trigger_source, requested_by, model, content::text AS content, error_code, error_message,
            input_tokens, output_tokens, requested_at, completed_at
            """;

    private final JdbcTemplate jdbc;

    public AiReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPending(AiReportType type, long databaseConfigId, String databaseName, LocalDate reportDate,
                              Instant windowStart, Instant windowEnd, AiTriggerSource trigger, Long requestedBy,
                              String model, Instant requestedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_reports (report_type, database_config_id, database_name, report_date,
                        window_start, window_end, status, trigger_source, requested_by, model, requested_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, type.name());
            statement.setLong(2, databaseConfigId);
            statement.setString(3, databaseName);
            if (reportDate == null) statement.setNull(4, Types.DATE);
            else statement.setDate(4, Date.valueOf(reportDate));
            statement.setObject(5, utc(windowStart));
            statement.setObject(6, utc(windowEnd));
            statement.setString(7, trigger.name());
            if (requestedBy == null) statement.setNull(8, Types.BIGINT);
            else statement.setLong(8, requestedBy);
            statement.setString(9, model);
            statement.setObject(10, utc(requestedAt));
            return statement;
        }, keys);
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    /** @param model 실제로 생성한 모델. null이면 요청 시 기록한 값을 유지한다. */
    public void complete(long id, String contentJson, long inputTokens, long outputTokens, String model,
                         Instant completedAt) {
        jdbc.update("""
                UPDATE ai_reports SET status = 'SUCCEEDED', content = CAST(? AS jsonb), input_tokens = ?,
                    output_tokens = ?, model = COALESCE(?, model), completed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, contentJson, inputTokens, outputTokens, model, utc(completedAt), id);
    }

    public void fail(long id, String errorCode, String errorMessage, Instant completedAt) {
        jdbc.update("""
                UPDATE ai_reports SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, errorCode, truncate(errorMessage), utc(completedAt), id);
    }

    /** 이전 프로세스가 끝내지 못한 PENDING을 정리한다. 단일 인스턴스 배포 전제. */
    public int failAllPending(String errorCode, String errorMessage, Instant completedAt) {
        return jdbc.update("""
                UPDATE ai_reports SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?
                WHERE status = 'PENDING'
                """, errorCode, truncate(errorMessage), utc(completedAt));
    }

    public Optional<AiReportRow> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_reports WHERE id = ?", AiReportStore::row, id)
                .stream().findFirst();
    }

    /** 해당 날짜에 성공했거나 진행 중인 일일 보고서가 있는지. 스케줄러 중복 생성 방지용. */
    public boolean hasDailyReport(long databaseConfigId, LocalDate reportDate) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ai_reports
                               WHERE report_type = 'DAILY_REPORT' AND database_config_id = ? AND report_date = ?
                                 AND status IN ('PENDING', 'SUCCEEDED'))
                """, Boolean.class, databaseConfigId, Date.valueOf(reportDate));
        return Boolean.TRUE.equals(exists);
    }

    public long count(Long databaseConfigId, AiReportType type, AiReportStatus status, LocalDate reportDate) {
        Filter filter = filter(databaseConfigId, type, status, reportDate);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_reports" + filter.where(), Long.class,
                filter.args().toArray());
        return count == null ? 0 : count;
    }

    public List<AiReportRow> page(Long databaseConfigId, AiReportType type, AiReportStatus status,
                                  LocalDate reportDate, int page, int size) {
        Filter filter = filter(databaseConfigId, type, status, reportDate);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_reports" + filter.where()
                + " ORDER BY requested_at DESC, id DESC LIMIT ? OFFSET ?", AiReportStore::row, args.toArray());
    }

    public int deleteRequestedBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM ai_reports WHERE requested_at < ? AND status <> 'PENDING'", utc(cutoff));
    }

    private static Filter filter(Long databaseConfigId, AiReportType type, AiReportStatus status,
                                 LocalDate reportDate) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (databaseConfigId != null) {
            clauses.add("database_config_id = ?");
            args.add(databaseConfigId);
        }
        if (type != null) {
            clauses.add("report_type = ?");
            args.add(type.name());
        }
        if (status != null) {
            clauses.add("status = ?");
            args.add(status.name());
        }
        if (reportDate != null) {
            clauses.add("report_date = ?");
            args.add(Date.valueOf(reportDate));
        }
        return new Filter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), args);
    }

    private static AiReportRow row(ResultSet rs, int rowNum) throws SQLException {
        Date reportDate = rs.getDate("report_date");
        long requestedBy = rs.getLong("requested_by");
        boolean requestedByNull = rs.wasNull();
        long inputTokens = rs.getLong("input_tokens");
        boolean inputNull = rs.wasNull();
        long outputTokens = rs.getLong("output_tokens");
        boolean outputNull = rs.wasNull();
        return new AiReportRow(
                rs.getLong("id"),
                AiReportType.valueOf(rs.getString("report_type")),
                rs.getLong("database_config_id"),
                rs.getString("database_name"),
                reportDate == null ? null : reportDate.toLocalDate(),
                instant(rs, "window_start"),
                instant(rs, "window_end"),
                AiReportStatus.valueOf(rs.getString("status")),
                AiTriggerSource.valueOf(rs.getString("trigger_source")),
                requestedByNull ? null : requestedBy,
                rs.getString("model"),
                rs.getString("content"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                inputNull ? null : inputTokens,
                outputNull ? null : outputTokens,
                instant(rs, "requested_at"),
                instant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record Filter(String where, List<Object> args) {
    }

    public record AiReportRow(
            long id,
            AiReportType type,
            long databaseConfigId,
            String databaseName,
            LocalDate reportDate,
            Instant windowStart,
            Instant windowEnd,
            AiReportStatus status,
            AiTriggerSource triggerSource,
            Long requestedBy,
            String model,
            String contentJson,
            String errorCode,
            String errorMessage,
            Long inputTokens,
            Long outputTokens,
            Instant requestedAt,
            Instant completedAt
    ) {
    }
}
