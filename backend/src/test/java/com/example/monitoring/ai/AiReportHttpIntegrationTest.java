package com.example.monitoring.ai;

import com.example.monitoring.ai.llm.AiGenerationException;
import com.example.monitoring.ai.llm.AiInsightClient;
import com.example.monitoring.ai.model.AiFinding;
import com.example.monitoring.ai.model.DailyReportInsight;
import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.InsightSeverity;
import com.example.monitoring.ai.model.OverallHealth;
import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.auth.service.PasswordHashingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 보고서 API를 실제 HTTP·PostgreSQL로 검증한다. LLM은 가짜 구현으로 바꾼다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.collector.enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.outbox.retention-cleanup-enabled=false",
        "app.database-security.verify-on-startup=false",
        "monitoring.risk.enabled=false",
        "monitoring.realtime.enabled=false",
        "monitoring.notifications.enabled=false",
        "monitoring.retention.enabled=false",
        "spring.task.scheduling.enabled=false",
        "monitoring.ai.enabled=true",
        "monitoring.ai.gemini-api-key=test-key-not-used",
        "monitoring.ai.daily-report-zone=UTC",
        "monitoring.ai.daily-report-schedule-enabled=false"
})
@ActiveProfiles("local")
class AiReportHttpIntegrationTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_KEYS = "{\"1\":\"" + KEY + "\"}";
    private static final EmbeddedPostgres POSTGRES;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        try {
            System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
            System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", DB_KEYS);
            System.setProperty("LEGACY_TIME_ZONE", "Asia/Seoul");
            POSTGRES = EmbeddedPostgres.start();
        } catch (Exception exception) {
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
        registry.add("app.database-security.allowed-cidrs", () -> "127.0.0.1/32");
        // 아무것도 듣지 않는 포트. 쿼리 분석이 대상 접속 실패를 FAILED로 남기는지 본다.
        registry.add("app.database-security.allowed-ports", () -> "1");
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
        System.clearProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
        System.clearProperty("DB_CONFIG_ENCRYPTION_KEYS");
        System.clearProperty("LEGACY_TIME_ZONE");
    }

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountRepository users;
    @Autowired private AuthSessionRepository sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private PasswordHashingService passwords;

    @MockBean private StringRedisTemplate redis;
    @MockBean private AiInsightClient aiClient;

    private String adminToken;
    private String userToken;
    private long targetId;
    private LocalDate yesterday;

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("TRUNCATE TABLE ai_reports, incidents, metric_data, audit_logs, access_logs, auth_sessions, "
                + "database_configs, users RESTART IDENTITY CASCADE");
        reset(redis, aiClient);
        doReturn(0L).when(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any());
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(aiClient.model()).thenReturn("gemini-3.6-flash");

        adminToken = token(UserRole.ADMIN);
        userToken = token(UserRole.USER);
        targetId = createTarget("ai-target");
        yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    }

    @Test
    void statusReportsAvailabilityToAnyAuthenticatedUser() throws Exception {
        JsonNode status = json(send("GET", "/api/v1/ai/status", userToken), 200);
        assertThat(status.path("available").asBoolean()).isTrue();
        assertThat(status.path("provider").asText()).isEqualTo("gemini");
        assertThat(status.path("model").asText()).isEqualTo("gemini-3.6-flash");
        assertThat(status.path("timeZone").asText()).isEqualTo("UTC");
        assertThat(status.path("dailyReportScheduled").asBoolean()).isFalse();
        assertThat(send("GET", "/api/v1/ai/status", null).statusCode()).isEqualTo(401);
    }

    @Test
    void dailyReportIsGeneratedAsynchronouslyFromAggregatedMetrics() throws Exception {
        Instant dayStart = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
        insertMetric(dayStart.plusSeconds(3_600), "SUCCESS", 40L, 100L, 120.5, 2L, 12L, 1_000L);
        insertMetric(dayStart.plusSeconds(3_605), "SUCCESS", 90L, 100L, 300.0, 8L, 30L, 1_200L);
        insertMetric(dayStart.plusSeconds(7_200), "CONNECTION_FAILED", null, null, null, null, 5_000L, null);
        insertMetric(dayStart.minusSeconds(60), "SUCCESS", 10L, 100L, 50.0, 0L, 8L, 900L);
        insertIncident(dayStart.plusSeconds(3_605));
        when(aiClient.dailyReport(eq("ai-target"), eq(yesterday), eq("UTC"), any(), any()))
                .thenReturn(new AiInsightClient.Result<>(new DailyReportInsight(
                        "어제는 01시에 연결 사용률이 90%까지 올랐습니다.", OverallHealth.WARNING, 72,
                        List.of(new AiFinding(InsightSeverity.WARNING, "연결 사용률 급증", "01시 90%")),
                        List.of("max_connections 여유를 확인하세요.")), 1_234, 321, "gemini-3.5-flash"));

        JsonNode pending = json(send("POST", "/api/v1/databases/" + targetId + "/ai/daily-report", adminToken), 202);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("reportDate").asText()).isEqualTo(yesterday.toString());
        assertThat(pending.path("triggerSource").asText()).isEqualTo("MANUAL");

        JsonNode done = await(pending.path("id").asLong(), userToken);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("inputTokens").asLong()).isEqualTo(1_234);
        assertThat(done.path("model").asText()).isEqualTo("gemini-3.5-flash");
        JsonNode report = done.path("dailyReport");
        assertThat(report.path("healthScore").asInt()).isEqualTo(72);
        assertThat(report.path("overallStatus").asText()).isEqualTo("WARNING");
        assertThat(report.path("findings").get(0).path("title").asText()).isEqualTo("연결 사용률 급증");
        JsonNode stats = report.path("stats");
        assertThat(stats.path("sampleCount").asLong()).isEqualTo(3);
        assertThat(stats.path("connectionFailedCount").asLong()).isEqualTo(1);
        assertThat(stats.path("availabilityPercent").asDouble()).isEqualTo(66.67);
        assertThat(stats.path("maxConnectionUsagePercent").asDouble()).isEqualTo(90.0);
        assertThat(stats.path("slowQueriesTotal").asLong()).isEqualTo(10);
        assertThat(stats.path("storageBytesStart").asLong()).isEqualTo(1_000);
        assertThat(stats.path("storageBytesEnd").asLong()).isEqualTo(1_200);
        assertThat(stats.path("errorCounts").path("CONNECT_TIMEOUT").asLong()).isEqualTo(1);
        assertThat(stats.path("incidentCount").asLong()).isEqualTo(1);
        assertThat(stats.path("hourly")).hasSize(2);
        assertThat(report.path("previousDayStats").path("sampleCount").asLong()).isEqualTo(1);

        verify(aiClient).dailyReport(eq("ai-target"), eq(yesterday), eq("UTC"),
                org.mockito.ArgumentMatchers.<DailyStats>argThat(value -> value.sampleCount() == 3),
                org.mockito.ArgumentMatchers.<DailyStats>argThat(value -> value.sampleCount() == 1));

        JsonNode page = json(send("GET", "/api/v1/ai/reports?databaseConfigId=" + targetId
                + "&type=DAILY_REPORT&date=" + yesterday, userToken), 200);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(pending.path("id").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'AI_REPORT_REQUESTED'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void generationFailureIsStoredWithItsCode() throws Exception {
        insertMetric(yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60), "SUCCESS",
                1L, 100L, 1.0, 0L, 5L, 10L);
        when(aiClient.dailyReport(any(), any(), any(), any(), any()))
                .thenThrow(new AiGenerationException(AiGenerationException.REFUSED, "AI가 거절했습니다."));

        JsonNode pending = json(send("POST", "/api/v1/databases/" + targetId + "/ai/daily-report?date="
                + yesterday, adminToken), 202);
        JsonNode done = await(pending.path("id").asLong(), adminToken);

        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("errorCode").asText()).isEqualTo("AI_REFUSED");
        assertThat(done.path("dailyReport").isNull()).isTrue();
    }

    @Test
    void secondRequestWhileGeneratingIsRejected() throws Exception {
        insertMetric(yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60), "SUCCESS",
                1L, 100L, 1.0, 0L, 5L, 10L);
        CountDownLatch release = new CountDownLatch(1);
        when(aiClient.dailyReport(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            throw new AiGenerationException(AiGenerationException.UPSTREAM_ERROR, "stop");
        });
        try {
            JsonNode first = json(send("POST", "/api/v1/databases/" + targetId + "/ai/daily-report", adminToken), 202);
            assertError(send("POST", "/api/v1/databases/" + targetId + "/ai/daily-report", adminToken),
                    409, "AI_REPORT_IN_PROGRESS");
            release.countDown();
            assertThat(await(first.path("id").asLong(), adminToken).path("status").asText()).isEqualTo("FAILED");
        } finally {
            release.countDown();
        }
    }

    @Test
    void requestValidationAndAuthorization() throws Exception {
        String path = "/api/v1/databases/" + targetId + "/ai/daily-report";
        assertError(send("POST", path, userToken), 403, "FORBIDDEN");
        assertError(send("POST", "/api/v1/databases/" + targetId + "/ai/query-analysis", userToken), 403, "FORBIDDEN");
        assertError(send("POST", path, null), 401, "AUTH_REQUIRED");
        assertError(send("POST", path + "?date=" + LocalDate.now(ZoneOffset.UTC), adminToken), 400, "VALIDATION_ERROR");
        assertError(send("POST", path + "?date=2026-13-01", adminToken), 400, "VALIDATION_ERROR");
        assertError(send("POST", path + "?date=" + LocalDate.now(ZoneOffset.UTC).minusDays(40), adminToken),
                400, "VALIDATION_ERROR");
        assertError(send("POST", path, adminToken), 422, "AI_NO_DATA");
        assertError(send("POST", "/api/v1/databases/999/ai/daily-report", adminToken), 404, "DATABASE_NOT_FOUND");
        assertError(send("GET", "/api/v1/ai/reports/999", userToken), 404, "AI_REPORT_NOT_FOUND");
        assertError(send("GET", "/api/v1/ai/reports?type=NOPE", userToken), 400, "VALIDATION_ERROR");
        assertError(send("GET", "/api/v1/ai/reports?size=101", userToken), 400, "VALIDATION_ERROR");
    }

    @Test
    void cooldownReturnsRetryAfter() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.getExpire(anyString())).thenReturn(42L);

        HttpResponse<String> response = send("POST", "/api/v1/databases/" + targetId + "/ai/query-analysis", adminToken);

        assertError(response, 429, "RATE_LIMITED");
        assertThat(response.headers().firstValue("Retry-After")).contains("42");
    }

    @Test
    void queryAnalysisRecordsUnreachableTarget() throws Exception {
        JsonNode pending = json(send("POST", "/api/v1/databases/" + targetId + "/ai/query-analysis", adminToken), 202);
        assertThat(pending.path("type").asText()).isEqualTo("QUERY_ANALYSIS");
        assertThat(pending.path("reportDate").isNull()).isTrue();

        JsonNode done = await(pending.path("id").asLong(), adminToken);

        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("errorCode").asText()).isEqualTo("TARGET_UNREACHABLE");
        assertThat(done.toString()).doesNotContain("fixture-password");
    }

    @Test
    void openApiDocumentsAiEndpoints() throws Exception {
        JsonNode api = json(send("GET", "/v3/api-docs", null), 200);
        JsonNode paths = api.path("paths");
        assertThat(paths.path("/api/v1/ai/status").path("get").isObject()).isTrue();
        assertThat(paths.path("/api/v1/ai/reports").path("get").isObject()).isTrue();
        assertThat(paths.path("/api/v1/ai/reports/{reportId}").path("get").isObject()).isTrue();
        assertThat(paths.path("/api/v1/databases/{id}/ai/daily-report").path("post").path("responses").has("202"))
                .isTrue();
        assertThat(paths.path("/api/v1/databases/{id}/ai/query-analysis").path("post").path("responses").has("202"))
                .isTrue();
        assertThat(api.path("components").path("schemas").path("AiReportResponse").path("properties")
                .has("dailyReport")).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode await(long reportId, String token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            JsonNode report = json(send("GET", "/api/v1/ai/reports/" + reportId, token), 200);
            if (!"PENDING".equals(report.path("status").asText())) return report;
            Thread.sleep(50);
        }
        throw new AssertionError("AI report " + reportId + " stayed PENDING");
    }

    private void insertMetric(Instant at, String status, Long active, Long max, Double qps, Long slowDelta,
                              Long responseMs, Long storage) {
        boolean failed = "CONNECTION_FAILED".equals(status);
        jdbc.update("""
                INSERT INTO metric_data (database_config_id, config_version, timestamp, collection_attempt_time,
                    collection_status, active_connections, max_connections, qps, slow_queries_delta,
                    response_time_ms, storage_bytes, error_code, created_at)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, targetId, Timestamp.from(at), Timestamp.from(at), status, active, max, qps, slowDelta,
                responseMs, storage, failed ? "CONNECT_TIMEOUT" : null, Timestamp.from(at));
    }

    private void insertIncident(Instant openedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents (incident_id, database_config_id, database_name, rule_id, rule_type, severity,
                    status, opened_at, last_observed_at, resolved_at, resolution_reason, metric_name, metric_value,
                    threshold_value, source_metric_id, source_event_id, message, incident_version)
                VALUES (?, ?, 'ai-target', 'CONNECTION_RATIO', 'CONNECTION_RATIO_EXCEEDED', 'WARNING', 'RESOLVED',
                    ?, ?, ?, 'RECOVERED', 'activeConnectionsRatio', 0.9, 0.8, NULL, NULL, 'ratio high', 2)
                """, id, targetId, Timestamp.from(openedAt), Timestamp.from(openedAt),
                Timestamp.from(openedAt.plusSeconds(30)));
    }

    private long createTarget(String name) throws Exception {
        JsonNode created = json(send("POST", "/api/v1/databases", adminToken,
                "{\"name\":\"" + name + "\",\"host\":\"127.0.0.1\",\"port\":1,"
                        + "\"databaseName\":\"app\",\"username\":\"fixture\","
                        + "\"password\":\"fixture-password\",\"enabled\":false}"), 201);
        return created.path("id").asLong();
    }

    private String token(UserRole role) {
        UserAccount account = users.saveAndFlush(UserAccount.builder()
                .email(role.name().toLowerCase() + '-' + UUID.randomUUID() + "@example.test")
                .displayName(role.name())
                .passwordHash(passwords.hash("ai-contract-password"))
                .role(role)
                .enabled(true)
                .build());
        Instant now = Instant.now();
        AuthSession session = sessions.saveAndFlush(AuthSession.builder()
                .id(UUID.randomUUID())
                .user(account)
                .currentRefreshHash(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .createdAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(3_600))
                .authVersion(account.getAuthVersion())
                .build());
        return accessTokens.issue(account, session.getId()).value();
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return send(method, path, token, null);
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) builder.header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return objectMapper.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
