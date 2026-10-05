package com.example.monitoring.integration;

import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.auth.service.AuthenticationService;
import com.example.monitoring.auth.service.PasswordHashingService;
import com.example.monitoring.auth.service.RefreshTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.collector.enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.legacy.blocked-reasons-cleanup-enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.outbox.retention-cleanup-enabled=false",
        "app.database-security.verify-on-startup=false",
        "monitoring.risk.enabled=false",
        "monitoring.realtime.enabled=false",
        "monitoring.notifications.enabled=false",
        "monitoring.retention.enabled=false",
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("local")
@Execution(ExecutionMode.SAME_THREAD)
class PartCHttpContractTest {

    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private static final String SAFE_ID_OVERFLOW = "9007199254740992";
    private static final String UTC_MILLIS_PATTERN =
            "^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])"
                    + "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\\.[0-9]{3}Z$";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_KEYS = "{\"1\":\"" + KEY + "\"}";
    // Deterministic dummy webhook; no real provider credential is embedded.
    private static final String SLACK_URL =
            "https://hooks.slack.com/services/T00000000/B00000000/" + "X".repeat(24);
    private static final PartCTestEcFixture VAPID = PartCTestEcFixture.generate();
    private static final PartCTestEcFixture SUBSCRIBER = PartCTestEcFixture.generate();
    private static final String OFF_CURVE_P256DH = offCurvePublicKey();
    private static final String PUSH_AUTH = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    private static final String PREVIOUS_ACTIVE_KEY_VERSION =
            System.getProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
    private static final String PREVIOUS_ENCRYPTION_KEYS =
            System.getProperty("DB_CONFIG_ENCRYPTION_KEYS");
    private static final String PREVIOUS_LEGACY_TIME_ZONE =
            System.getProperty("LEGACY_TIME_ZONE");
    private static final EmbeddedPostgres POSTGRES;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    static {
        try {
            System.setProperty("DB_CONFIG_ACTIVE_KEY_VERSION", "1");
            System.setProperty("DB_CONFIG_ENCRYPTION_KEYS", DB_KEYS);
            System.setProperty("LEGACY_TIME_ZONE", "Asia/Seoul");
            POSTGRES = EmbeddedPostgres.start();
        } catch (Exception exception) {
            restoreSystemProperties();
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
        registry.add("app.database-security.allowed-ports", () -> "13306");
        registry.add("WEB_PUSH_VAPID_PUBLIC_KEY", VAPID::publicKey);
        registry.add("WEB_PUSH_VAPID_PRIVATE_KEY", VAPID::privateKey);
        registry.add("WEB_PUSH_VAPID_SUBJECT", () -> "mailto:part-c-contract@example.test");
        registry.add("PUSH_ALLOWED_HOSTS", () -> "fcm.googleapis.com");
    }

    @AfterAll
    static void closePostgres() throws Exception {
        try {
            PartCHttpEvidence.write(new ObjectMapper());
        } finally {
            try {
                POSTGRES.close();
            } finally {
                restoreSystemProperties();
            }
        }
    }

    private static void restoreSystemProperties() {
        restoreSystemProperty("DB_CONFIG_ACTIVE_KEY_VERSION", PREVIOUS_ACTIVE_KEY_VERSION);
        restoreSystemProperty("DB_CONFIG_ENCRYPTION_KEYS", PREVIOUS_ENCRYPTION_KEYS);
        restoreSystemProperty("LEGACY_TIME_ZONE", PREVIOUS_LEGACY_TIME_ZONE);
    }

    private static void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) System.clearProperty(name);
        else System.setProperty(name, previousValue);
    }

    @LocalServerPort
    private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountRepository users;
    @Autowired private AuthSessionRepository sessions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private PasswordHashingService passwords;
    @Autowired private RefreshTokenService refreshTokens;
    @Autowired private AuthenticationService authentication;

    @MockBean private StringRedisTemplate rateLimitStore;

    private UserAccount user;
    private UserAccount otherUser;
    private UserAccount admin;
    private SessionIdentity userSession;
    private SessionIdentity otherSession;
    private SessionIdentity adminSession;
    private String expiredToken;
    private long targetId;
    private PartCHttpDatabaseFixture database;

    @BeforeEach
    void seedCanonicalIdentitiesAndTarget() throws Exception {
        database = new PartCHttpDatabaseFixture(jdbc);
        database.dropAuditFailureTrigger();
        database.truncate();
        doReturn(0L).when(rateLimitStore).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any());

        user = account("partc-user", UserRole.USER);
        otherUser = account("partc-other", UserRole.USER);
        admin = account("partc-admin", UserRole.ADMIN);
        userSession = issue(user, Instant.now().plusSeconds(3_600));
        otherSession = issue(otherUser, Instant.now().plusSeconds(3_600));
        adminSession = issue(admin, Instant.now().plusSeconds(3_600));
        expiredToken = issue(user, Instant.now().minusSeconds(1)).token();
        targetId = createTarget("part-c-contract-target");
    }

    @AfterEach
    void clearFixtures() {
        database.dropAuditFailureTrigger();
        database.truncate();
    }

    @Test
    void authorizationManifestCoversEveryPartCRoute() throws Exception {
        UUID incidentId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        database.seedIncident(incidentId, targetId, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED",
                "WARNING", "RESOLVED", Instant.now().minusSeconds(120), "RECOVERED", 2L);

        assertRoleMatrix("GET", "/api/v1/databases/" + targetId + "/status", null,
                200, 200);
        assertRoleMatrix("GET", "/api/v1/databases/" + targetId + "/risk-policy", null,
                200, 200);
        assertRestrictedMutation("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                policyBody(database.currentPolicyVersion(targetId), 60, 600), 200);
        assertRoleMatrix("GET", "/api/v1/incidents?databaseConfigId=" + targetId, null,
                200, 200);
        assertRoleMatrix("GET", "/api/v1/incidents/" + incidentId, null,
                200, 200);
        assertRoleMatrix("GET", "/api/v1/notifications/push-config", null,
                200, 200);
        assertRoleMatrix("GET", "/api/v1/notifications/push-subscriptions", null,
                200, 200);

        JsonNode userPush = assertJson(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBody(pushEndpoint("matrix-user"), null)), 201);
        JsonNode adminPush = assertJson(request("POST", "/api/v1/notifications/push-subscriptions",
                adminSession.token(), pushBody(pushEndpoint("matrix-admin"), null)), 201);
        assertExpiredAndAnonymous("POST", "/api/v1/notifications/push-subscriptions",
                pushBody(pushEndpoint("matrix-denied"), null));
        assertResponse(request("DELETE", "/api/v1/notifications/push-subscriptions/"
                + userPush.path("id").asLong(), userSession.token(), null), 204);
        assertResponse(request("DELETE", "/api/v1/notifications/push-subscriptions/"
                + adminPush.path("id").asLong(), adminSession.token(), null), 204);
        assertExpiredAndAnonymous("DELETE", "/api/v1/notifications/push-subscriptions/" + MAX_SAFE_ID, null);

        assertAdminOnly("GET", "/api/v1/notifications/webhooks?page=0&size=20", null, 200);
        assertError(request("POST", "/api/v1/notifications/webhooks", userSession.token(),
                webhookBody("matrix", SLACK_URL, true)), 403, "FORBIDDEN");
        assertExpiredAndAnonymous("POST", "/api/v1/notifications/webhooks",
                webhookBody("matrix", SLACK_URL, true));
        JsonNode webhook = assertJson(request("POST", "/api/v1/notifications/webhooks",
                adminSession.token(), webhookBody("matrix", SLACK_URL, true)), 201);
        long webhookId = webhook.path("id").asLong();
        assertError(request("PATCH", "/api/v1/notifications/webhooks/" + webhookId,
                userSession.token(), "{\"name\":\"forbidden\"}"), 403, "FORBIDDEN");
        assertExpiredAndAnonymous("PATCH", "/api/v1/notifications/webhooks/" + webhookId,
                "{\"name\":\"denied\"}");
        assertJson(request("PATCH", "/api/v1/notifications/webhooks/" + webhookId,
                adminSession.token(), "{\"name\":\"matrix-updated\"}"), 200);
        assertError(request("DELETE", "/api/v1/notifications/webhooks/" + webhookId,
                userSession.token(), null), 403, "FORBIDDEN");
        assertExpiredAndAnonymous("DELETE", "/api/v1/notifications/webhooks/" + webhookId, null);
        assertResponse(request("DELETE", "/api/v1/notifications/webhooks/" + webhookId,
                adminSession.token(), null), 204);
        assertAdminOnly("GET", "/api/v1/notifications/deliveries?page=0&size=20", null, 200);
        PartCHttpEvidence.recordScenario("part-c authorization matrix", Map.of(
                "coveredOperations", 14,
                "anonymousCode", "AUTH_REQUIRED",
                "expiredCode", "SESSION_REVOKED",
                "insufficientRoleCode", "FORBIDDEN"));
    }

    @Test
    void statusPolicyAndIncidentReadsUseExactFieldsOrderingPagingFiltersAndDeletedHistory() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID firstAtTie = UUID.fromString("20000000-0000-0000-0000-00000000000a");
        UUID secondAtTie = UUID.fromString("20000000-0000-0000-0000-00000000000b");
        UUID older = UUID.fromString("20000000-0000-0000-0000-000000000003");
        database.seedIncident(firstAtTie, targetId, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED",
                "WARNING", "OPEN", now.minusSeconds(60), null, 1L);
        database.seedIncident(secondAtTie, targetId, "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH",
                "CRITICAL", "RESOLVED", now.minusSeconds(60), "RECOVERED", 2L);
        database.seedIncident(older, targetId, "CONNECTION_FAILURE", "CONNECTION_FAILURE",
                "FATAL", "RESOLVED", now.minusSeconds(120), "RECOVERED", 3L);
        jdbc.update("UPDATE monitoring_states SET risk_level='WARNING', state_version=7, updated_at=? "
                + "WHERE database_config_id=?", Timestamp.from(now), targetId);

        JsonNode status = assertJson(request("GET", "/api/v1/databases/" + targetId + "/status",
                userSession.token(), null), 200);
        assertFields(status, "databaseConfigId", "configVersion", "deleted", "enabled",
                "connectionStatus", "dataFreshness", "riskLevel", "lastAttemptAt", "lastSuccessAt",
                "latestMetricId", "openIncidentIds", "stateVersion", "updatedAt");
        assertSafeId(status.path("databaseConfigId"));
        assertSafeId(status.path("configVersion"));
        assertSafeId(status.path("stateVersion"));
        assertThat(status.path("deleted").asBoolean()).isFalse();
        assertThat(status.path("enabled").asBoolean()).isTrue();
        assertThat(status.path("connectionStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(status.path("dataFreshness").asText()).isEqualTo("NO_DATA");
        assertThat(status.path("riskLevel").asText()).isEqualTo("WARNING");
        assertThat(status.path("lastAttemptAt").isNull()).isTrue();
        assertThat(status.path("lastSuccessAt").isNull()).isTrue();
        assertThat(status.path("latestMetricId").isNull()).isTrue();
        assertThat(status.path("openIncidentIds").size()).isEqualTo(1);
        assertThat(status.path("openIncidentIds").get(0).asText()).isEqualTo(firstAtTie.toString());
        assertUtcMillis(status.path("updatedAt"));
        assertNoSecrets(status.toString());

        JsonNode policy = assertJson(request("GET", "/api/v1/databases/" + targetId + "/risk-policy",
                adminSession.token(), null), 200);
        assertPolicy(policy, targetId, 1L, 30, 300);

        String window = "start=" + encode(now.minusSeconds(300).toString())
                + "&end=" + encode(now.plusSeconds(1).toString());
        JsonNode firstPage = assertJson(request("GET", "/api/v1/incidents?" + window
                + "&databaseConfigId=" + targetId + "&page=0&size=2",
                userSession.token(), null), 200);
        assertPage(firstPage, 0, 2, 3, 2);
        assertThat(firstPage.path("items").get(0).path("incidentId").asText())
                .isEqualTo(firstAtTie.toString());
        assertThat(firstPage.path("items").get(1).path("incidentId").asText())
                .isEqualTo(secondAtTie.toString());
        assertIncident(firstPage.path("items").get(0));
        JsonNode secondPage = assertJson(request("GET", "/api/v1/incidents?" + window
                + "&databaseConfigId=" + targetId + "&page=1&size=2",
                adminSession.token(), null), 200);
        assertPage(secondPage, 1, 2, 3, 2);
        assertThat(secondPage.path("items").get(0).path("incidentId").asText())
                .isEqualTo(older.toString());

        JsonNode filtered = assertJson(request("GET", "/api/v1/incidents?" + window
                + "&databaseConfigId=" + targetId + "&severity=CRITICAL&status=RESOLVED",
                userSession.token(), null), 200);
        assertPage(filtered, 0, 20, 1, 1);
        assertThat(filtered.path("items").get(0).path("incidentId").asText())
                .isEqualTo(secondAtTie.toString());

        JsonNode missingTarget = assertJson(request("GET", "/api/v1/incidents?" + window
                + "&databaseConfigId=" + MAX_SAFE_ID, userSession.token(), null), 200);
        assertPage(missingTarget, 0, 20, 0, 0);

        long deletedTarget = createTarget("deleted-history-target");
        UUID deletedHistory = UUID.fromString("20000000-0000-0000-0000-000000000004");
        database.seedIncident(deletedHistory, deletedTarget, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED",
                "WARNING", "RESOLVED", now.minusSeconds(30), "TARGET_DELETED", 2L);
        assertResponse(request("DELETE", "/api/v1/databases/" + deletedTarget,
                adminSession.token(), null), 204);
        JsonNode history = assertJson(request("GET", "/api/v1/incidents?" + window
                + "&databaseConfigId=" + deletedTarget, userSession.token(), null), 200);
        assertPage(history, 0, 20, 1, 1);
        assertThat(history.path("items").get(0).path("incidentId").asText())
                .isEqualTo(deletedHistory.toString());
        assertIncident(assertJson(request("GET", "/api/v1/incidents/" + deletedHistory,
                userSession.token(), null), 200));
        assertError(request("GET", "/api/v1/databases/" + deletedTarget + "/status",
                userSession.token(), null), 404, "DATABASE_NOT_FOUND");
        assertError(request("GET", "/api/v1/databases/" + deletedTarget + "/risk-policy",
                userSession.token(), null), 404, "DATABASE_NOT_FOUND");
        PartCHttpEvidence.recordScenario("status policy incident reads", Map.of(
                "statusFieldCount", 13,
                "policyRuleCount", 2,
                "incidentFieldCount", incidentFields().size(),
                "orderedIncidentCount", 3,
                "filteredIncidentCount", 1,
                "deletedTargetHistoryCount", 1));
    }

    @Test
    void failureManifest() throws Exception {
        PartCHttpDatabaseFixture.BusinessSnapshot initial = database.snapshot();
        assertValidation(request("GET", "/api/v1/databases/" + SAFE_ID_OVERFLOW + "/status",
                userSession.token(), null), "id", "OUT_OF_RANGE");
        assertValidation(request("GET", "/api/v1/databases/not-an-id/risk-policy",
                userSession.token(), null), "id", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/incidents/984b0ae3",
                userSession.token(), null), "incidentId", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/incidents/"
                        + "70000000-0000-0000-0000-00000000000A",
                userSession.token(), null), "incidentId", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/incidents?databaseConfigId=" + SAFE_ID_OVERFLOW,
                userSession.token(), null), "databaseConfigId", "OUT_OF_RANGE");
        assertValidation(request("GET", "/api/v1/incidents?"
                        + "start=2026-09-28T00%3A00%3A00Z&end=2026-09-28T00%3A01%3A00.000Z",
                userSession.token(), null), "start", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/incidents?start=2026-09-28T00%3A00%3A00.000Z",
                userSession.token(), null), "end", "REQUIRED");
        assertValidation(request("GET", "/api/v1/incidents?start=2026-09-28T00%3A00%3A00.000Z"
                        + "&end=2026-10-29T00%3A00%3A00.001Z",
                userSession.token(), null), "start", "OUT_OF_RANGE");
        assertValidation(request("GET", "/api/v1/incidents?page=0&size=101",
                userSession.token(), null), "size", "OUT_OF_RANGE");
        assertValidation(request("GET", "/api/v1/incidents?severity=INFO",
                userSession.token(), null), "severity", "INVALID_VALUE");
        assertValidation(request("GET", "/api/v1/incidents?status=CANCELLED",
                userSession.token(), null), "status", "INVALID_VALUE");
        assertError(request("GET", "/api/v1/incidents/70000000-0000-0000-0000-000000000001",
                userSession.token(), null), 404, "INCIDENT_NOT_FOUND");
        assertThat(database.snapshot()).isEqualTo(initial);

        long version = database.currentPolicyVersion(targetId);
        assertError(request("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                userSession.token(), policyBody(version, 60, 600)), 403, "FORBIDDEN");
        assertError(request("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                adminSession.token(), policyBody(version + 99, 60, 600)), 409,
                "POLICY_VERSION_CONFLICT");
        assertValidation(request("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                adminSession.token(), policyBodyWithDuplicateRules(version)), "rules", "INVALID_VALUE");
        assertMalformedPolicyBody(policyBodyMissingFatalThreshold(version));
        String canonicalPolicy = policyBody(version, 60, 600);
        assertMalformedPolicyBody(canonicalPolicy.replace("\"version\":" + version,
                "\"version\":" + version + ".5"));
        assertMalformedPolicyBody(canonicalPolicy.replace("\"staleAfterSeconds\":60",
                "\"staleAfterSeconds\":60.5"));
        assertMalformedPolicyBody(canonicalPolicy.replace("\"operator\":\"GTE\"",
                "\"operator\":1"));
        assertMalformedPolicyBody(canonicalPolicy.replace("\"warningThreshold\":0.80",
                "\"warningThreshold\":\"0.80\""));
        assertMalformedPolicyBody(canonicalPolicy.replace("\"enabled\":true",
                "\"enabled\":\"true\""));
        assertError(request("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                expiredToken, policyBody(version, 60, 600)), 401, "SESSION_REVOKED");
        assertThat(database.snapshot()).isEqualTo(initial);

        JsonNode push = assertJson(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBody(pushEndpoint("foreign-delete"), null)), 201);
        assertThat(push.path("expirationTime").isNull()).isTrue();
        PartCHttpDatabaseFixture.BusinessSnapshot afterPush = database.snapshot();
        assertError(request("DELETE", "/api/v1/notifications/push-subscriptions/"
                + push.path("id").asLong(), otherSession.token(), null), 404,
                "SUBSCRIPTION_NOT_FOUND");
        assertValidation(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBodyWithKeys(pushEndpoint("bad-key"), "AA", PUSH_AUTH)),
                "keys.p256dh", "INVALID_VALUE");
        assertValidation(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBodyWithKeys(pushEndpoint("off-curve"),
                        OFF_CURVE_P256DH, PUSH_AUTH)), "keys.p256dh", "INVALID_VALUE");
        assertValidation(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBodyWithKeys(pushEndpoint("bad-auth"),
                        SUBSCRIBER.publicKey(), "AA")), "keys.auth", "INVALID_VALUE");
        assertValidation(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBody("https://example.test/push", null)),
                "endpoint", "INVALID_VALUE");
        assertValidation(request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBody(pushEndpoint("expired"),
                        Instant.now().minusSeconds(1).toEpochMilli())),
                "expirationTime", "INVALID_VALUE");
        JsonNode missingExpiration = assertError(request("POST",
                        "/api/v1/notifications/push-subscriptions", userSession.token(),
                        pushBodyWithoutExpiration(pushEndpoint("missing-expiration"))),
                400, "VALIDATION_ERROR");
        assertThat(missingExpiration.path("fieldErrors")).isEmpty();
        assertValidation(request("DELETE", "/api/v1/notifications/push-subscriptions/"
                + SAFE_ID_OVERFLOW, userSession.token(), null), "id", "OUT_OF_RANGE");
        assertThat(database.snapshot()).isEqualTo(afterPush);

        assertError(request("POST", "/api/v1/notifications/webhooks", userSession.token(),
                webhookBody("forbidden", SLACK_URL, true)), 403, "FORBIDDEN");
        assertValidation(request("POST", "/api/v1/notifications/webhooks", adminSession.token(),
                webhookBody("invalid", "https://example.test/services/a/b/c", true)),
                "url", "INVALID_VALUE");
        assertValidation(request("POST", "/api/v1/notifications/webhooks", adminSession.token(),
                "{\"name\":\"invalid-provider\",\"provider\":\"TEAMS\",\"url\":\""
                        + SLACK_URL + "\",\"enabled\":true}"), "provider", "INVALID_VALUE");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?channel=EMAIL",
                adminSession.token(), null), "channel", "INVALID_VALUE");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?"
                        + "incidentId=984b0ae3",
                adminSession.token(), null), "incidentId", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?"
                        + "incidentId=70000000-0000-0000-0000-00000000000A",
                adminSession.token(), null), "incidentId", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?page=10001",
                adminSession.token(), null), "page", "OUT_OF_RANGE");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?"
                        + "start=2026-09-28T00%3A00%3A00.000Z",
                adminSession.token(), null), "end", "REQUIRED");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?"
                        + "start=2026-09-28T00%3A00%3A00Z"
                        + "&end=2026-09-28T00%3A01%3A00.000Z",
                adminSession.token(), null), "start", "INVALID_FORMAT");
        assertValidation(request("GET", "/api/v1/notifications/deliveries?"
                        + "start=2026-09-28T00%3A00%3A00.000Z"
                        + "&end=2026-10-29T00%3A00%3A00.001Z",
                adminSession.token(), null), "start", "OUT_OF_RANGE");
        assertValidation(request("GET", "/api/v1/notifications/webhooks?size=101",
                adminSession.token(), null), "size", "OUT_OF_RANGE");
        assertValidation(request("PATCH", "/api/v1/notifications/webhooks/not-an-id",
                adminSession.token(), "{\"name\":\"unchanged\"}"), "id", "INVALID_FORMAT");
        assertValidation(request("DELETE", "/api/v1/notifications/webhooks/" + SAFE_ID_OVERFLOW,
                adminSession.token(), null), "id", "OUT_OF_RANGE");
        assertValidation(request("PATCH", "/api/v1/notifications/webhooks/" + MAX_SAFE_ID,
                adminSession.token(), "{}"), "body", "REQUIRED");
        assertError(request("PATCH", "/api/v1/notifications/webhooks/" + MAX_SAFE_ID,
                adminSession.token(), "{\"name\":\"missing\"}"), 404, "WEBHOOK_NOT_FOUND");
        assertThat(database.snapshot()).isEqualTo(afterPush);
        assertThat(failureAuditCount("POLICY_UPDATED")).isEqualTo(10L);
        assertThat(failureAuditCount("PUSH_REGISTERED")).isEqualTo(6L);
        assertThat(failureAuditCount("PUSH_DELETED")).isEqualTo(2L);
        assertThat(failureAuditCount("WEBHOOK_CREATED")).isEqualTo(3L);
        assertThat(failureAuditCount("WEBHOOK_UPDATED")).isEqualTo(3L);
        assertThat(failureAuditCount("WEBHOOK_DELETED")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE result='SUCCESS' "
                + "AND action IN ('POLICY_UPDATED','PUSH_DELETED','WEBHOOK_CREATED',"
                + "'WEBHOOK_UPDATED','WEBHOOK_DELETED')", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE result='SUCCESS' "
                + "AND action='PUSH_REGISTERED'", Long.class)).isEqualTo(1L);
        jdbc.queryForList("SELECT summary FROM audit_logs WHERE result='FAILURE'", String.class)
                .forEach(this::assertNoSecrets);
        PartCHttpEvidence.recordScenario("strict validation and failure atomicity", Map.of(
                "safeIdOverflowStatus", 400,
                "canonicalUuidStatus", 400,
                "windowLimitStatus", 400,
                "pagingLimitStatus", 400,
                "policyConflictStatus", 409,
                "foreignPushDeleteStatus", 404,
                "failureAuditCount", 25,
                "businessSnapshotUnchanged", true));
    }

    @Test
    void policyUpdateClosesOnlyConfigurableStateAndCommitsAuditAndOutboxAtomically() throws Exception {
        Instant observedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        UUID ratio = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID slow = UUID.fromString("30000000-0000-0000-0000-000000000002");
        UUID system = UUID.fromString("30000000-0000-0000-0000-000000000003");
        database.seedIncident(ratio, targetId, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED",
                "WARNING", "OPEN", observedAt, null, 1L);
        database.seedIncident(slow, targetId, "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH",
                "CRITICAL", "OPEN", observedAt, null, 1L);
        database.seedIncident(system, targetId, "CONNECTION_FAILURE", "CONNECTION_FAILURE",
                "FATAL", "OPEN", observedAt, null, 1L);
        database.seedRuleClock(targetId, "CONNECTION_RATIO", observedAt);
        database.seedRuleClock(targetId, "SLOW_QUERY_RATE", observedAt);
        database.seedRuleClock(targetId, "CONNECTION_FAILURE", observedAt);
        long webhookId = database.seedWebhook("atomic-recipient", true);
        long ratioDelivery = database.seedDelivery(ratio, 1L, "SLACK", webhookId, "PENDING",
                observedAt, null, null);
        long slowDelivery = database.seedDelivery(slow, 1L, "SLACK", webhookId, "PENDING",
                observedAt.plusMillis(1), null, null);
        long systemDelivery = database.seedDelivery(system, 1L, "SLACK", webhookId, "PENDING",
                observedAt.plusMillis(2), null, null);
        jdbc.update("UPDATE monitoring_states SET risk_level='FATAL', state_version=7, updated_at=? "
                + "WHERE database_config_id=?", Timestamp.from(observedAt), targetId);
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM audit_logs");

        long version = database.currentPolicyVersion(targetId);
        JsonNode updated = assertJson(request("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                adminSession.token(), policyBody(version, 60, 600)), 200);
        assertPolicy(updated, targetId, version + 1, 60, 600);

        database.assertIncidentResolution(ratio, "RESOLVED", "POLICY_CHANGED", 2L);
        database.assertIncidentResolution(slow, "RESOLVED", "POLICY_CHANGED", 2L);
        database.assertIncidentResolution(system, "OPEN", null, 1L);
        database.assertDeliveryState(ratioDelivery, "CANCELLED", false);
        database.assertDeliveryState(slowDelivery, "CANCELLED", false);
        database.assertDeliveryState(systemDelivery, "PENDING", true);
        assertThat(jdbc.queryForList("SELECT rule_id FROM risk_rule_states "
                + "WHERE database_config_id=? ORDER BY rule_id", String.class, targetId))
                .containsExactly("CONNECTION_FAILURE");
        Map<String, Object> state = jdbc.queryForMap("SELECT state_version, risk_level FROM monitoring_states "
                + "WHERE database_config_id=?", targetId);
        assertThat(((Number) state.get("state_version")).longValue()).isEqualTo(8L);
        assertThat(state.get("risk_level")).isEqualTo("FATAL");

        List<Map<String, Object>> outbox = jdbc.queryForList(
                "SELECT seq,event_type,ordering_key,payload::text AS payload FROM event_outbox ORDER BY seq");
        assertThat(outbox).hasSize(3);
        outbox.stream().map(row -> (String) row.get("payload")).forEach(this::assertNoSecrets);
        assertThat(outbox.stream().map(row -> row.get("event_type")))
                .containsExactly("IncidentResolvedEvent", "IncidentResolvedEvent",
                        "MonitoringStatusChangedEvent");
        assertThat(outbox.stream().map(row -> row.get("ordering_key")))
                .containsOnly("database:" + targetId);
        assertThat(objectMapper.readTree((String) outbox.get(0).get("payload"))
                .path("incidentId").asText()).isEqualTo(ratio.toString());
        assertThat(objectMapper.readTree((String) outbox.get(1).get("payload"))
                .path("incidentId").asText()).isEqualTo(slow.toString());
        JsonNode statusEvent = objectMapper.readTree((String) outbox.get(2).get("payload"));
        assertThat(statusEvent.path("stateVersion").asLong()).isEqualTo(8L);
        assertThat(statusEvent.path("openIncidentIds").size()).isEqualTo(1);
        assertThat(statusEvent.path("openIncidentIds").get(0).asText()).isEqualTo(system.toString());

        List<Map<String, Object>> audits = jdbc.queryForList("""
                SELECT actor_id,action,target_type,target_id,database_config_id,result,request_id,summary
                FROM audit_logs WHERE action='POLICY_UPDATED' ORDER BY id
                """);
        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(((Number) audit.get("actor_id")).longValue()).isEqualTo(admin.getId());
        assertThat(audit.get("target_type")).isEqualTo("POLICY");
        assertThat(audit.get("target_id")).isEqualTo(Long.toString(targetId));
        assertThat(((Number) audit.get("database_config_id")).longValue()).isEqualTo(targetId);
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        UUID.fromString(audit.get("request_id").toString());
        assertThat(audit.get("summary").toString()).doesNotContain("endpoint", "password", "url");
        PartCHttpEvidence.recordScenario("policy update atomic commit", Map.of(
                "configurableIncidentsResolved", 2,
                "systemIncidentsOpen", 1,
                "pendingDeliveriesCancelled", 2,
                "systemDeliveryPending", 1,
                "stateVersion", 8,
                "outboxOrder", List.of("IncidentResolvedEvent", "IncidentResolvedEvent",
                        "MonitoringStatusChangedEvent"),
                "successAuditCount", 1));
    }

    @Test
    void policyUpdateRollsBackEveryBusinessWriteWhenSuccessAuditFails() throws Exception {
        Instant observedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        UUID incident = UUID.fromString("31000000-0000-0000-0000-000000000001");
        database.seedIncident(incident, targetId, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED",
                "WARNING", "OPEN", observedAt, null, 1L);
        database.seedRuleClock(targetId, "CONNECTION_RATIO", observedAt);
        long webhookId = database.seedWebhook("rollback-recipient", true);
        database.seedDelivery(incident, 1L, "SLACK", webhookId, "PENDING", observedAt, null, null);
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM audit_logs");
        PartCHttpDatabaseFixture.BusinessSnapshot before = database.snapshot();
        database.installAuditFailureTrigger();
        try {
            HttpResponse<String> response = request("PUT", "/api/v1/databases/" + targetId
                    + "/risk-policy", adminSession.token(),
                    policyBody(database.currentPolicyVersion(targetId), 60, 600));
            assertError(response, 500, "INTERNAL_ERROR");
        } finally {
            database.dropAuditFailureTrigger();
        }
        assertThat(database.snapshot()).isEqualTo(before);
        List<Map<String, Object>> failures = jdbc.queryForList("""
                SELECT action,result,target_type,target_id,database_config_id
                FROM audit_logs WHERE action='POLICY_UPDATED' ORDER BY id
                """);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).get("result")).isEqualTo("FAILURE");
        assertThat(failures.get(0).get("target_type")).isEqualTo("POLICY");
        assertThat(failures.get(0).get("target_id")).isEqualTo(Long.toString(targetId));
        PartCHttpEvidence.recordScenario("policy update audit failure rollback", Map.of(
                "httpStatus", 500,
                "businessSnapshotUnchanged", true,
                "failureAuditCount", 1));
    }

    @Test
    void pushCrudEnforcesOwnershipRenewalLimitTombstonesSecretsAndSessionRevocation() throws Exception {
        JsonNode config = assertJson(request("GET", "/api/v1/notifications/push-config",
                userSession.token(), null), 200);
        assertFields(config, "publicKey");
        assertThat(config.path("publicKey").asText()).isEqualTo(VAPID.publicKey());
        assertNoSecrets(config.toString());

        String endpoint = pushEndpoint("owner-device");
        long expiration = Instant.now().plusSeconds(3_600).toEpochMilli();
        HttpResponse<String> createdResponse = request("POST", "/api/v1/notifications/push-subscriptions",
                userSession.token(), pushBody(endpoint, expiration));
        JsonNode created = assertJson(createdResponse, 201);
        assertThat(createdResponse.headers().firstValue("Location").orElseThrow())
                .isEqualTo("/api/v1/notifications/push-subscriptions/" + created.path("id").asLong());
        assertPush(created);
        long originalId = created.path("id").asLong();

        SessionIdentity renewedSession = issue(user, Instant.now().plusSeconds(3_600));
        long renewedExpiration = Instant.now().plusSeconds(7_200).toEpochMilli();
        HttpResponse<String> renewedResponse = request("POST",
                "/api/v1/notifications/push-subscriptions",
                renewedSession.token(), pushBodyWithKeys(endpoint, SUBSCRIBER.publicKey(),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                                new byte[]{16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1}),
                        renewedExpiration));
        JsonNode renewed = assertJson(renewedResponse, 200);
        assertThat(renewedResponse.headers().firstValue("Location")).isEmpty();
        assertPush(renewed);
        assertThat(renewed.path("id").asLong()).isEqualTo(originalId);
        assertThat(renewed.path("createdAt")).isEqualTo(created.path("createdAt"));
        assertThat(renewed.path("expirationTime").asLong()).isEqualTo(renewedExpiration);
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT user_id,sid,endpoint_hash,payload_nonce,payload_ciphertext,enabled,deleted_at
                FROM push_subscriptions WHERE id=?
                """, originalId);
        assertThat(((Number) stored.get("user_id")).longValue()).isEqualTo(user.getId());
        assertThat(stored.get("sid").toString()).isEqualTo(renewedSession.sessionId().toString());
        assertThat((byte[]) stored.get("endpoint_hash")).hasSize(32);
        assertThat((byte[]) stored.get("payload_nonce")).hasSize(12);
        assertThat((byte[]) stored.get("payload_ciphertext")).hasSizeGreaterThan(16);
        assertThat(stored.get("enabled")).isEqualTo(true);
        assertThat(stored.get("deleted_at")).isNull();
        assertNoSecrets(renewed.toString());

        assertError(request("POST", "/api/v1/notifications/push-subscriptions",
                otherSession.token(), pushBody(endpoint, null)), 409, "RESOURCE_LIMIT_EXCEEDED");
        for (int index = 1; index < 10; index++) {
            assertJson(request("POST", "/api/v1/notifications/push-subscriptions",
                    renewedSession.token(), pushBody(pushEndpoint("owner-" + index), null)), 201);
        }
        assertThat(database.activePushCount(user.getId())).isEqualTo(10);
        PartCHttpDatabaseFixture.BusinessSnapshot atLimit = database.snapshot();
        assertError(request("POST", "/api/v1/notifications/push-subscriptions",
                renewedSession.token(), pushBody(pushEndpoint("owner-eleven"), null)),
                409, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(database.snapshot()).isEqualTo(atLimit);

        JsonNode listed = assertJson(request("GET", "/api/v1/notifications/push-subscriptions",
                renewedSession.token(), null), 200);
        assertThat(listed.isArray()).isTrue();
        assertThat(listed.size()).isEqualTo(10);
        long previous = 0;
        for (JsonNode item : listed) {
            assertPush(item);
            assertThat(item.path("id").asLong()).isGreaterThan(previous);
            previous = item.path("id").asLong();
        }

        UUID deleteIncident = UUID.fromString("40000000-0000-0000-0000-000000000001");
        Instant deleteAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        database.seedIncident(deleteIncident, targetId, "CONNECTION_FAILURE", "CONNECTION_FAILURE",
                "FATAL", "OPEN", deleteAt, null, 1L);
        long deleteDelivery = database.seedDelivery(deleteIncident, 1L, "WEB_PUSH", originalId,
                "PENDING", deleteAt, null, null);
        assertError(request("DELETE", "/api/v1/notifications/push-subscriptions/" + originalId,
                otherSession.token(), null), 404, "SUBSCRIPTION_NOT_FOUND");
        database.assertDeliveryState(deleteDelivery, "PENDING", true);
        assertResponse(request("DELETE", "/api/v1/notifications/push-subscriptions/" + originalId,
                renewedSession.token(), null), 204);
        database.assertDeliveryState(deleteDelivery, "CANCELLED", false);
        Map<String, Object> tombstone = jdbc.queryForMap(
                "SELECT user_id,enabled,deleted_at,updated_at FROM push_subscriptions WHERE id=?", originalId);
        assertThat(((Number) tombstone.get("user_id")).longValue()).isEqualTo(user.getId());
        assertThat(tombstone.get("enabled")).isEqualTo(false);
        assertThat(tombstone.get("deleted_at")).isNotNull().isEqualTo(tombstone.get("updated_at"));
        assertError(request("DELETE", "/api/v1/notifications/push-subscriptions/" + originalId,
                renewedSession.token(), null), 404, "SUBSCRIPTION_NOT_FOUND");

        JsonNode transferred = assertJson(request("POST", "/api/v1/notifications/push-subscriptions",
                otherSession.token(), pushBody(endpoint, null)), 201);
        long transferredId = transferred.path("id").asLong();
        assertThat(transferredId).isNotEqualTo(originalId);
        assertThat(jdbc.queryForObject("SELECT user_id FROM push_subscriptions WHERE id=?",
                Long.class, originalId)).isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("SELECT user_id FROM push_subscriptions WHERE id=?",
                Long.class, transferredId)).isEqualTo(otherUser.getId());

        UserAccount revocationUser = account("partc-revocation", UserRole.USER);
        String rawRefresh = refreshTokens.generate();
        SessionIdentity revocationSession = issue(revocationUser,
                Instant.now().plusSeconds(3_600), refreshTokens.hash(rawRefresh));
        JsonNode revocable = assertJson(request("POST", "/api/v1/notifications/push-subscriptions",
                revocationSession.token(), pushBody(pushEndpoint("revocable"), null)), 201);
        UUID incident = UUID.fromString("40000000-0000-0000-0000-000000000002");
        Instant now = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        database.seedIncident(incident, targetId, "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH",
                "FATAL", "OPEN", now, null, 1L);
        long deliveryId = database.seedDelivery(incident, 1L, "WEB_PUSH", revocable.path("id").asLong(),
                "PENDING", now, null, null);
        authentication.logout(rawRefresh);
        Map<String, Object> revoked = jdbc.queryForMap(
                "SELECT sid,enabled,deleted_at,updated_at FROM push_subscriptions WHERE id=?",
                revocable.path("id").asLong());
        assertThat(revoked.get("enabled")).isEqualTo(false);
        assertThat(revoked.get("deleted_at")).isNotNull().isEqualTo(revoked.get("updated_at"));
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_sessions WHERE sid=?",
                Boolean.class, revocationSession.sessionId())).isTrue();
        database.assertDeliveryNotSent(deliveryId);
        assertError(request("GET", "/api/v1/notifications/push-subscriptions",
                revocationSession.token(), null), 401, "SESSION_REVOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs "
                + "WHERE action='PUSH_REGISTERED' AND result='SUCCESS'", Long.class)).isEqualTo(13L);
        Map<String, Object> deleteAudit = jdbc.queryForMap("""
                SELECT target_type,target_id,result,summary
                FROM audit_logs WHERE action='PUSH_DELETED' AND result='SUCCESS'
                """);
        assertThat(deleteAudit.get("target_type")).isEqualTo("PUSH_SUBSCRIPTION");
        assertThat(deleteAudit.get("target_id")).isEqualTo(Long.toString(originalId));
        assertThat(deleteAudit.get("result")).isEqualTo("SUCCESS");
        assertNoSecrets(deleteAudit.get("summary").toString());
        jdbc.queryForList("SELECT summary FROM audit_logs WHERE action='PUSH_REGISTERED' "
                        + "AND result='SUCCESS'", String.class)
                .forEach(this::assertNoSecrets);
        PartCHttpEvidence.recordScenario("push recipient lifecycle", Map.of(
                "publicConfigFieldCount", 1,
                "activeRecipientLimit", 10,
                "renewalReusedId", true,
                "explicitDeleteCancelledPending", 1,
                "logoutTombstonedSubscription", true,
                "logoutDeliverySent", false,
                "revokedSessionStatus", 401));
    }

    @Test
    void webhookCrudEnforcesAdminLimitOrderingIdempotencyCancellationAndSecretRedaction() throws Exception {
        for (int codePoints : List.of(60, 100)) {
            String boundaryName = "😀".repeat(codePoints);
            JsonNode boundary = assertJson(request("POST", "/api/v1/notifications/webhooks",
                    adminSession.token(), webhookBody(boundaryName, slackUrl(100 + codePoints), true)), 201);
            assertWebhook(boundary);
            assertThat(boundary.path("name").asText().codePointCount(
                    0, boundary.path("name").asText().length())).isEqualTo(codePoints);
            assertResponse(request("DELETE", "/api/v1/notifications/webhooks/"
                    + boundary.path("id").asLong(), adminSession.token(), null), 204);
        }
        assertValidation(request("POST", "/api/v1/notifications/webhooks", adminSession.token(),
                webhookBody("😀".repeat(101), slackUrl(201), true)), "name", "INVALID_VALUE");

        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            HttpResponse<String> response = request("POST", "/api/v1/notifications/webhooks",
                    adminSession.token(), webhookBody(" hook-" + index + " ",
                            slackUrl(index), index % 2 == 0));
            JsonNode created = assertJson(response, 201);
            assertWebhook(created);
            assertThat(created.path("name").asText()).isEqualTo("hook-" + index);
            assertThat(response.headers().firstValue("Location").orElseThrow())
                    .isEqualTo("/api/v1/notifications/webhooks/" + created.path("id").asLong());
            ids.add(created.path("id").asLong());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_webhooks "
                + "WHERE deleted_at IS NULL", Long.class)).isEqualTo(10L);
        assertError(request("POST", "/api/v1/notifications/webhooks", adminSession.token(),
                webhookBody("eleventh", slackUrl(99), true)), 409, "RESOURCE_LIMIT_EXCEEDED");

        JsonNode firstPage = assertJson(request("GET", "/api/v1/notifications/webhooks?page=0&size=4",
                adminSession.token(), null), 200);
        assertPage(firstPage, 0, 4, 10, 3);
        for (int index = 0; index < 4; index++) {
            assertWebhook(firstPage.path("items").get(index));
            assertThat(firstPage.path("items").get(index).path("id").asLong()).isEqualTo(ids.get(index));
        }

        UUID incident = UUID.fromString("50000000-0000-0000-0000-000000000001");
        Instant now = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        database.seedIncident(incident, targetId, "CONNECTION_FAILURE", "CONNECTION_FAILURE",
                "FATAL", "OPEN", now, null, 1L);
        long delivery = database.seedDelivery(incident, 1L, "SLACK", ids.get(0), "PENDING",
                now, null, null);
        byte[] oldCiphertext = jdbc.queryForObject(
                "SELECT url_ciphertext FROM notification_webhooks WHERE id=?", byte[].class, ids.get(0));
        JsonNode patched = assertJson(request("PATCH", "/api/v1/notifications/webhooks/" + ids.get(0),
                adminSession.token(), "{\"name\":\"primary\",\"url\":\"" + slackUrl(42)
                        + "\",\"enabled\":false}"), 200);
        assertWebhook(patched);
        assertThat(patched.path("name").asText()).isEqualTo("primary");
        assertThat(patched.path("enabled").asBoolean()).isFalse();
        byte[] newCiphertext = jdbc.queryForObject(
                "SELECT url_ciphertext FROM notification_webhooks WHERE id=?", byte[].class, ids.get(0));
        assertThat(newCiphertext).isNotEqualTo(oldCiphertext);
        database.assertDeliveryState(delivery, "CANCELLED", false);
        assertNoSecrets(patched.toString());

        assertResponse(request("DELETE", "/api/v1/notifications/webhooks/" + ids.get(1),
                adminSession.token(), null), 204);
        assertResponse(request("DELETE", "/api/v1/notifications/webhooks/" + ids.get(1),
                adminSession.token(), null), 204);
        assertResponse(request("DELETE", "/api/v1/notifications/webhooks/" + MAX_SAFE_ID,
                adminSession.token(), null), 204);
        Map<String, Object> deleted = jdbc.queryForMap(
                "SELECT enabled,deleted_at,updated_at FROM notification_webhooks WHERE id=?", ids.get(1));
        assertThat(deleted.get("enabled")).isEqualTo(false);
        assertThat(deleted.get("deleted_at")).isNotNull().isEqualTo(deleted.get("updated_at"));
        JsonNode afterDelete = assertJson(request("GET", "/api/v1/notifications/webhooks?page=0&size=20",
                adminSession.token(), null), 200);
        assertPage(afterDelete, 0, 20, 9, 1);
        assertThat(afterDelete.path("items").findValuesAsText("id"))
                .doesNotContain(Long.toString(ids.get(1)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs "
                + "WHERE action='WEBHOOK_CREATED' AND result='SUCCESS'", Long.class)).isEqualTo(12L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs "
                + "WHERE action='WEBHOOK_UPDATED' AND result='SUCCESS'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs "
                + "WHERE action='WEBHOOK_DELETED' AND result='SUCCESS'", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForList("SELECT DISTINCT target_type FROM audit_logs "
                + "WHERE action LIKE 'WEBHOOK_%' AND result='SUCCESS'", String.class))
                .containsExactly("WEBHOOK");
        jdbc.queryForList("SELECT summary FROM audit_logs WHERE action LIKE 'WEBHOOK_%' "
                        + "AND result='SUCCESS'", String.class)
                .forEach(this::assertNoSecrets);
        PartCHttpEvidence.recordScenario("webhook recipient lifecycle", Map.of(
                "activeRecipientLimit", 10,
                "orderedFirstPageSize", 4,
                "disableCancelledPending", 1,
                "idempotentDeleteStatuses", List.of(204, 204),
                "remainingRecipients", 9));
    }

    @Test
    void deliveryHistoryUsesExactPublicFieldsHalfOpenFiltersAndStableOrdering() throws Exception {
        Instant base = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        long webhookId = database.seedWebhook("delivery-recipient", true);
        UUID first = UUID.fromString("60000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("60000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("60000000-0000-0000-0000-000000000003");
        UUID endExclusive = UUID.fromString("60000000-0000-0000-0000-000000000004");
        database.seedIncident(first, targetId, "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED",
                "WARNING", "RESOLVED", base, "RECOVERED", 2L);
        database.seedIncident(second, targetId, "SLOW_QUERY_RATE", "SLOW_QUERIES_HIGH",
                "CRITICAL", "RESOLVED", base.plusSeconds(1), "RECOVERED", 2L);
        database.seedIncident(third, targetId, "CONNECTION_FAILURE", "CONNECTION_FAILURE",
                "FATAL", "RESOLVED", base.plusSeconds(2), "RECOVERED", 2L);
        database.seedIncident(endExclusive, targetId, "COLLECTION_STALE", "COLLECTION_STALE",
                "CRITICAL", "RESOLVED", base.plusSeconds(3), "RECOVERED", 2L);
        long older = database.seedDelivery(first, 2L, "SLACK", webhookId, "FAILED",
                base, null, "PROVIDER_ERROR");
        long tieLower = database.seedDelivery(second, 2L, "SLACK", webhookId, "PENDING",
                base.plusSeconds(10), null, null);
        long tieHigher = database.seedDelivery(third, 2L, "SLACK", webhookId, "SENT",
                base.plusSeconds(10), base.plusSeconds(11), null);
        database.seedDelivery(endExclusive, 2L, "SLACK", webhookId, "CANCELLED",
                base.plusSeconds(20), null, null);

        String window = "start=" + encode(base.toString())
                + "&end=" + encode(base.plusSeconds(20).toString());
        JsonNode page = assertJson(request("GET", "/api/v1/notifications/deliveries?" + window
                + "&page=0&size=2", adminSession.token(), null), 200);
        assertPage(page, 0, 2, 3, 2);
        assertDelivery(page.path("items").get(0));
        assertDelivery(page.path("items").get(1));
        assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(tieHigher);
        assertThat(page.path("items").get(1).path("id").asLong()).isEqualTo(tieLower);

        JsonNode secondPage = assertJson(request("GET", "/api/v1/notifications/deliveries?" + window
                + "&page=1&size=2", adminSession.token(), null), 200);
        assertPage(secondPage, 1, 2, 3, 2);
        assertThat(secondPage.path("items").get(0).path("id").asLong()).isEqualTo(older);

        JsonNode incidentFilter = assertJson(request("GET", "/api/v1/notifications/deliveries?"
                + window + "&incidentId=" + second, adminSession.token(), null), 200);
        assertPage(incidentFilter, 0, 20, 1, 1);
        assertThat(incidentFilter.path("items").get(0).path("id").asLong()).isEqualTo(tieLower);

        JsonNode enumFilters = assertJson(request("GET", "/api/v1/notifications/deliveries?"
                + window + "&channel=SLACK&status=SENT", adminSession.token(), null), 200);
        assertPage(enumFilters, 0, 20, 1, 1);
        assertThat(enumFilters.path("items").get(0).path("id").asLong()).isEqualTo(tieHigher);
        assertNoSecrets(page.toString());
        PartCHttpEvidence.recordScenario("delivery history query", Map.of(
                "halfOpenWindowCount", 3,
                "firstPageSize", 2,
                "stableTieOrder", List.of(tieHigher, tieLower),
                "incidentFilterCount", 1,
                "enumFilterCount", 1,
                "publicFieldCount", 10));
    }

    @Test
    void generatedOpenApiDescribesEveryPartCRouteAndOnlyPublicSchemas() throws Exception {
        JsonNode api = assertJson(request("GET", "/v3/api-docs", null, null), 200);
        PartCHttpOpenApiAssertions schema = new PartCHttpOpenApiAssertions(api);
        schema.operation("/api/v1/databases/{id}/status", "get", "200", "400", "401", "403", "404");
        schema.operation("/api/v1/databases/{id}/risk-policy", "get", "200", "400", "401", "403", "404");
        schema.operation("/api/v1/databases/{id}/risk-policy", "put", "200", "400", "401", "403", "404", "409");
        schema.operation("/api/v1/incidents", "get", "200", "400", "401", "403");
        schema.operation("/api/v1/incidents/{incidentId}", "get", "200", "400", "401", "403", "404");
        schema.operation("/api/v1/notifications/push-config", "get", "200", "401", "403", "503");
        schema.operation("/api/v1/notifications/push-subscriptions", "get", "200", "401", "403");
        schema.operation("/api/v1/notifications/push-subscriptions", "post", "200", "201", "400", "401", "403", "409");
        schema.operation("/api/v1/notifications/push-subscriptions/{id}", "delete", "204", "400", "401", "403", "404");
        schema.operation("/api/v1/notifications/webhooks", "get", "200", "400", "401", "403");
        schema.operation("/api/v1/notifications/webhooks", "post", "201", "400", "401", "403", "409");
        schema.operation("/api/v1/notifications/webhooks/{id}", "patch", "200", "400", "401", "403", "404");
        schema.operation("/api/v1/notifications/webhooks/{id}", "delete", "204", "400", "401", "403");
        schema.operation("/api/v1/notifications/deliveries", "get", "200", "400", "401", "403");

        schema.noContentResponse("/api/v1/notifications/push-subscriptions/{id}", "delete");
        schema.noContentResponse("/api/v1/notifications/webhooks/{id}", "delete");
        schema.errorSchemas();
        JsonNode apiError = schema.resolvedSchema(
                api.path("components").path("schemas").path("ApiErrorResponse"));
        Set<String> apiErrorFields = Set.of("code", "message", "requestId", "fieldErrors");
        schema.schemaFields(apiError, apiErrorFields);
        schema.requiredFields(apiError, apiErrorFields);
        JsonNode fieldErrors = schema.resolvedSchema(
                apiError.path("properties").path("fieldErrors"));
        assertThat(fieldErrors.path("type").asText()).isEqualTo("array");
        JsonNode fieldError = schema.resolvedSchema(fieldErrors.path("items"));
        Set<String> fieldErrorFields = Set.of("field", "code", "message");
        schema.schemaFields(fieldError, fieldErrorFields);
        schema.requiredFields(fieldError, fieldErrorFields);
        schema.schemaFields(schema.successSchema("/api/v1/databases/{id}/status", "get", "200"),
                Set.of("databaseConfigId", "configVersion", "deleted", "enabled", "connectionStatus",
                        "dataFreshness", "riskLevel", "lastAttemptAt", "lastSuccessAt", "latestMetricId",
                        "openIncidentIds", "stateVersion", "updatedAt"));
        JsonNode policySchema = schema.resolvedSchema(
                schema.successSchema("/api/v1/databases/{id}/risk-policy", "get", "200"));
        schema.schemaFields(policySchema,
                Set.of("databaseConfigId", "version", "staleAfterSeconds", "notificationCooldownSeconds",
                        "rules", "updatedAt"));
        Set<String> policyRuleFields = Set.of(
                "ruleId", "metricName", "operator", "warningThreshold", "criticalThreshold",
                "fatalThreshold", "sustainSeconds", "recoverySeconds", "enabled");
        JsonNode ruleArray = schema.resolvedSchema(policySchema.path("properties").path("rules"));
        assertThat(ruleArray.path("type").asText()).isEqualTo("array");
        schema.schemaFields(ruleArray.path("items"), policyRuleFields);
        JsonNode policyWrite = schema.resolvedSchema(
                schema.requestSchema("/api/v1/databases/{id}/risk-policy", "put"));
        Set<String> policyWriteFields = Set.of(
                "version", "staleAfterSeconds", "notificationCooldownSeconds", "rules");
        schema.schemaFields(policyWrite, policyWriteFields);
        schema.requiredFields(policyWrite, policyWriteFields);
        JsonNode policyWriteRules = schema.resolvedSchema(
                policyWrite.path("properties").path("rules"));
        assertThat(policyWriteRules.path("type").asText()).isEqualTo("array");
        JsonNode policyRuleWrite = schema.resolvedSchema(policyWriteRules.path("items"));
        schema.schemaFields(policyRuleWrite, policyRuleFields);
        schema.requiredFields(policyRuleWrite, policyRuleFields);
        schema.schemaFields(schema.pageItemSchema(
                        schema.successSchema("/api/v1/incidents", "get", "200")),
                incidentFields());
        schema.schemaFields(schema.successSchema("/api/v1/incidents/{incidentId}", "get", "200"),
                incidentFields());
        schema.schemaFields(schema.successSchema("/api/v1/notifications/push-config", "get", "200"),
                Set.of("publicKey"));
        JsonNode pushList = schema.resolvedSchema(
                schema.successSchema("/api/v1/notifications/push-subscriptions", "get", "200"));
        assertThat(pushList.path("type").asText()).isEqualTo("array");
        schema.schemaFields(pushList.path("items"),
                Set.of("id", "createdAt", "updatedAt", "expirationTime"));
        schema.schemaFields(
                schema.successSchema("/api/v1/notifications/push-subscriptions", "post", "200"),
                Set.of("id", "createdAt", "updatedAt", "expirationTime"));
        schema.schemaFields(
                schema.successSchema("/api/v1/notifications/push-subscriptions", "post", "201"),
                Set.of("id", "createdAt", "updatedAt", "expirationTime"));
        Set<String> pushInputFields = Set.of("endpoint", "expirationTime", "keys");
        JsonNode pushInput = schema.resolvedSchema(
                schema.requestSchema("/api/v1/notifications/push-subscriptions", "post"));
        schema.schemaFields(pushInput, pushInputFields);
        schema.requiredFields(pushInput, pushInputFields);
        assertThat(pushInput.path("properties").path("endpoint").path("maxLength").asInt())
                .isEqualTo(2_048);
        JsonNode expiration = pushInput.path("properties").path("expirationTime");
        assertThat(expiration.path("type").asText()).isEqualTo("integer");
        assertThat(expiration.path("format").asText()).isEqualTo("int64");
        assertThat(expiration.path("nullable").asBoolean()).isTrue();
        JsonNode pushKeys = schema.resolvedSchema(pushInput.path("properties").path("keys"));
        Set<String> pushKeyFields = Set.of("p256dh", "auth");
        schema.schemaFields(pushKeys, pushKeyFields);
        schema.requiredFields(pushKeys, pushKeyFields);
        schema.schemaFields(schema.pageItemSchema(
                        schema.successSchema("/api/v1/notifications/webhooks", "get", "200")),
                Set.of("id", "name", "provider", "enabled", "createdAt", "updatedAt"));
        schema.schemaFields(
                schema.successSchema("/api/v1/notifications/webhooks", "post", "201"),
                Set.of("id", "name", "provider", "enabled", "createdAt", "updatedAt"));
        schema.schemaFields(
                schema.successSchema("/api/v1/notifications/webhooks/{id}", "patch", "200"),
                Set.of("id", "name", "provider", "enabled", "createdAt", "updatedAt"));
        Set<String> webhookInputFields = Set.of("name", "provider", "url", "enabled");
        JsonNode webhookInput = schema.resolvedSchema(
                schema.requestSchema("/api/v1/notifications/webhooks", "post"));
        schema.schemaFields(webhookInput, webhookInputFields);
        schema.requiredFields(webhookInput, webhookInputFields);
        assertThat(webhookInput.path("properties").path("name").path("maxLength").asInt())
                .isEqualTo(100);
        assertThat(webhookInput.path("properties").path("url").path("maxLength").asInt())
                .isEqualTo(2_048);
        List<String> webhookProviders = new ArrayList<>();
        webhookInput.path("properties").path("provider").path("enum")
                .forEach(value -> webhookProviders.add(value.asText()));
        assertThat(webhookProviders).containsExactly("SLACK");
        JsonNode webhookPatch = schema.resolvedSchema(
                schema.requestSchema("/api/v1/notifications/webhooks/{id}", "patch"));
        schema.schemaFields(webhookPatch, Set.of("name", "url", "enabled"));
        schema.requiredFields(webhookPatch, Set.of());
        schema.schemaFields(schema.pageItemSchema(
                        schema.successSchema("/api/v1/notifications/deliveries", "get", "200")),
                Set.of("id", "incidentId", "incidentVersion", "channel", "recipientId", "status",
                        "attemptCount", "lastErrorCode", "createdAt", "sentAt"));

        schema.queryParameter("/api/v1/incidents", "databaseConfigId", "integer", "int64",
                "1", Long.toString(MAX_SAFE_ID), null, Set.of());
        schema.queryParameter("/api/v1/incidents", "severity", "string", null,
                null, null, null, Set.of("WARNING", "CRITICAL", "FATAL"));
        schema.queryParameter("/api/v1/incidents", "status", "string", null,
                null, null, null, Set.of("OPEN", "RESOLVED"));
        schema.queryParameter("/api/v1/incidents", "page", "integer", "int32",
                "0", "10000", "0", Set.of());
        schema.queryParameter("/api/v1/incidents", "size", "integer", "int32",
                "1", "100", "20", Set.of());
        schema.pathIdParameter("/api/v1/databases/{id}/status", "get", "id");
        schema.pathIdParameter("/api/v1/databases/{id}/risk-policy", "get", "id");
        schema.pathIdParameter("/api/v1/databases/{id}/risk-policy", "put", "id");
        schema.pathIdParameter("/api/v1/notifications/push-subscriptions/{id}", "delete", "id");
        schema.pathIdParameter("/api/v1/notifications/webhooks/{id}", "patch", "id");
        schema.pathIdParameter("/api/v1/notifications/webhooks/{id}", "delete", "id");

        JsonNode incidentId = schema.parameter("/api/v1/incidents/{incidentId}", "get", "incidentId");
        assertThat(incidentId.path("in").asText()).isEqualTo("path");
        JsonNode incidentIdSchema = incidentId.path("schema");
        assertThat(incidentIdSchema.path("type").asText()).isEqualTo("string");
        assertThat(incidentIdSchema.path("format").asText()).isEqualTo("uuid");
        assertThat(incidentIdSchema.path("pattern").asText()).isEqualTo(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        for (String name : List.of("start", "end")) {
            JsonNode time = schema.parameter("/api/v1/incidents", "get", name).path("schema");
            assertThat(time.path("type").asText()).isEqualTo("string");
            assertThat(time.path("format").asText()).isEqualTo("date-time");
            assertThat(time.path("pattern").asText()).isEqualTo(UTC_MILLIS_PATTERN);
            assertThat(time.path("example").asText()).matches(
                    "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        }

        schema.queryParameter("/api/v1/notifications/webhooks", "page", "integer", "int32",
                "0", "10000", "0", Set.of());
        schema.queryParameter("/api/v1/notifications/webhooks", "size", "integer", "int32",
                "1", "100", "20", Set.of());
        schema.queryParameter("/api/v1/notifications/deliveries", "channel", "string", null,
                null, null, null, Set.of("WEB_PUSH", "SLACK"));
        schema.queryParameter("/api/v1/notifications/deliveries", "status", "string", null,
                null, null, null, Set.of("PENDING", "SENT", "FAILED", "CANCELLED"));
        schema.queryParameter("/api/v1/notifications/deliveries", "page", "integer", "int32",
                "0", "10000", "0", Set.of());
        schema.queryParameter("/api/v1/notifications/deliveries", "size", "integer", "int32",
                "1", "100", "20", Set.of());
        JsonNode deliveryIncidentId = schema.parameter("/api/v1/notifications/deliveries", "get",
                "incidentId").path("schema");
        assertThat(deliveryIncidentId.path("type").asText()).isEqualTo("string");
        assertThat(deliveryIncidentId.path("format").asText()).isEqualTo("uuid");
        assertThat(deliveryIncidentId.path("pattern").asText()).isEqualTo(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        for (String name : List.of("start", "end")) {
            JsonNode time = schema.parameter("/api/v1/notifications/deliveries", "get", name)
                    .path("schema");
            assertThat(time.path("type").asText()).isEqualTo("string");
            assertThat(time.path("format").asText()).isEqualTo("date-time");
            assertThat(time.path("pattern").asText()).isEqualTo(UTC_MILLIS_PATTERN);
            assertThat(time.path("example").asText()).matches(
                    "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        }

        PartCHttpEvidence.recordScenario("part-c OpenAPI contract", Map.of(
                "documentedOperations", 14,
                "errorEnvelope", "ApiErrorResponse",
                "internalFieldsExposed", false));
    }

    private void assertRoleMatrix(String method, String path, String body,
                                  int expectedUser, int expectedAdmin) throws Exception {
        assertResponse(request(method, path, userSession.token(), body), expectedUser);
        assertResponse(request(method, path, adminSession.token(), body), expectedAdmin);
        assertExpiredAndAnonymous(method, path, body);
    }

    private void assertRestrictedMutation(String method, String path, String body,
                                          int expectedAdmin) throws Exception {
        assertError(request(method, path, userSession.token(), body), 403, "FORBIDDEN");
        assertExpiredAndAnonymous(method, path, body);
        assertResponse(request(method, path, adminSession.token(), body), expectedAdmin);
    }

    private void assertAdminOnly(String method, String path, String body,
                                 int expectedAdmin) throws Exception {
        assertError(request(method, path, userSession.token(), body), 403, "FORBIDDEN");
        assertResponse(request(method, path, adminSession.token(), body), expectedAdmin);
        assertExpiredAndAnonymous(method, path, body);
    }

    private void assertExpiredAndAnonymous(String method, String path, String body) throws Exception {
        assertError(request(method, path, expiredToken, body), 401, "SESSION_REVOKED");
        assertError(request(method, path, null, body), 401, "AUTH_REQUIRED");
    }

    private JsonNode assertJson(HttpResponse<String> response, int expectedStatus) throws Exception {
        assertResponse(response, expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isMissingNode()).isFalse();
        return body;
    }

    private void assertResponse(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
        UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow());
        if (expectedStatus == 204) {
            assertThat(response.body()).isEmpty();
        }
    }

    private JsonNode assertError(HttpResponse<String> response, int status, String code) throws Exception {
        JsonNode error = assertJson(response, status);
        assertFields(error, "code", "message", "requestId", "fieldErrors");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("requestId").asText())
                .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(error.path("fieldErrors").isArray()).isTrue();
        assertNoSecrets(error.toString());
        return error;
    }

    private void assertValidation(HttpResponse<String> response, String field, String code) throws Exception {
        JsonNode error = assertError(response, 400, "VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").size()).isEqualTo(1);
        JsonNode fieldError = error.path("fieldErrors").get(0);
        assertFields(fieldError, "field", "code", "message");
        assertThat(fieldError.path("field").asText()).isEqualTo(field);
        assertThat(fieldError.path("code").asText()).isEqualTo(code);
        assertThat(fieldError.path("message").asText()).isNotBlank();
    }

    private void assertMalformedPolicyBody(String body) throws Exception {
        JsonNode error = assertError(request("PUT", "/api/v1/databases/" + targetId + "/risk-policy",
                adminSession.token(), body), 400, "VALIDATION_ERROR");
        assertThat(error.path("fieldErrors")).isEmpty();
    }

    private long failureAuditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action=? AND result='FAILURE'",
                Long.class, action);
    }

    private void assertPolicy(JsonNode policy, long expectedTarget, long expectedVersion,
                              int staleSeconds, int cooldownSeconds) {
        assertFields(policy, "databaseConfigId", "version", "staleAfterSeconds",
                "notificationCooldownSeconds", "rules", "updatedAt");
        assertThat(policy.path("databaseConfigId").asLong()).isEqualTo(expectedTarget);
        assertThat(policy.path("version").asLong()).isEqualTo(expectedVersion);
        assertThat(policy.path("staleAfterSeconds").asInt()).isEqualTo(staleSeconds);
        assertThat(policy.path("notificationCooldownSeconds").asInt()).isEqualTo(cooldownSeconds);
        assertThat(policy.path("rules").size()).isEqualTo(2);
        assertThat(policy.path("rules").get(0).path("ruleId").asText()).isEqualTo("CONNECTION_RATIO");
        assertThat(policy.path("rules").get(1).path("ruleId").asText()).isEqualTo("SLOW_QUERY_RATE");
        JsonNode ratio = policy.path("rules").get(0);
        assertThat(ratio.path("metricName").asText()).isEqualTo("activeConnectionsRatio");
        assertThat(ratio.path("operator").asText()).isEqualTo("GTE");
        assertThat(ratio.path("warningThreshold").decimalValue()).isEqualByComparingTo("0.80");
        assertThat(ratio.path("criticalThreshold").decimalValue()).isEqualByComparingTo("0.90");
        assertThat(ratio.path("fatalThreshold").decimalValue()).isEqualByComparingTo("0.95");
        JsonNode slow = policy.path("rules").get(1);
        assertThat(slow.path("metricName").asText()).isEqualTo("slowQueriesPerSecond");
        assertThat(slow.path("operator").asText()).isEqualTo("GTE");
        assertThat(slow.path("warningThreshold").decimalValue()).isEqualByComparingTo("1.0");
        assertThat(slow.path("criticalThreshold").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(slow.path("fatalThreshold").isNull()).isTrue();
        for (JsonNode rule : policy.path("rules")) {
            assertFields(rule, "ruleId", "metricName", "operator", "warningThreshold",
                    "criticalThreshold", "fatalThreshold", "sustainSeconds", "recoverySeconds", "enabled");
            assertThat(rule.path("sustainSeconds").asInt()).isEqualTo(15);
            assertThat(rule.path("recoverySeconds").asInt()).isEqualTo(15);
            assertThat(rule.path("enabled").asBoolean()).isTrue();
        }
        assertUtcMillis(policy.path("updatedAt"));
        assertNoSecrets(policy.toString());
    }

    private void assertIncident(JsonNode incident) {
        assertFields(incident, incidentFields().toArray(String[]::new));
        assertCanonicalUuid(incident.path("incidentId"));
        assertSafeId(incident.path("databaseConfigId"));
        assertSafeId(incident.path("incidentVersion"));
        assertUtcMillis(incident.path("openedAt"));
        assertUtcMillis(incident.path("lastObservedAt"));
        if (!incident.path("resolvedAt").isNull()) assertUtcMillis(incident.path("resolvedAt"));
        assertThat(incident.path("severity").asText()).isIn("WARNING", "CRITICAL", "FATAL");
        assertThat(incident.path("status").asText()).isIn("OPEN", "RESOLVED");
        assertNoSecrets(incident.toString());
    }

    private Set<String> incidentFields() {
        return Set.of("incidentId", "databaseConfigId", "databaseName", "ruleId", "ruleType",
                "severity", "status", "openedAt", "lastObservedAt", "resolvedAt", "resolutionReason",
                "metricName", "metricValue", "thresholdValue", "sourceMetricId", "message",
                "incidentVersion");
    }

    private void assertPush(JsonNode push) {
        assertFields(push, "id", "createdAt", "updatedAt", "expirationTime");
        assertSafeId(push.path("id"));
        assertUtcMillis(push.path("createdAt"));
        assertUtcMillis(push.path("updatedAt"));
        assertNoSecrets(push.toString());
    }

    private void assertWebhook(JsonNode webhook) {
        assertFields(webhook, "id", "name", "provider", "enabled", "createdAt", "updatedAt");
        assertSafeId(webhook.path("id"));
        assertThat(webhook.path("provider").asText()).isEqualTo("SLACK");
        assertUtcMillis(webhook.path("createdAt"));
        assertUtcMillis(webhook.path("updatedAt"));
        assertNoSecrets(webhook.toString());
    }

    private void assertDelivery(JsonNode delivery) {
        assertFields(delivery, "id", "incidentId", "incidentVersion", "channel", "recipientId",
                "status", "attemptCount", "lastErrorCode", "createdAt", "sentAt");
        assertSafeId(delivery.path("id"));
        assertSafeId(delivery.path("incidentVersion"));
        assertSafeId(delivery.path("recipientId"));
        assertCanonicalUuid(delivery.path("incidentId"));
        assertThat(delivery.path("channel").asText()).isIn("WEB_PUSH", "SLACK");
        assertThat(delivery.path("status").asText()).isIn("PENDING", "SENT", "FAILED", "CANCELLED");
        assertThat(delivery.path("attemptCount").asInt()).isGreaterThanOrEqualTo(0);
        assertUtcMillis(delivery.path("createdAt"));
        if (!delivery.path("sentAt").isNull()) assertUtcMillis(delivery.path("sentAt"));
        assertNoSecrets(delivery.toString());
    }

    private void assertPage(JsonNode page, int number, int size, long total, int totalPages) {
        assertFields(page, "items", "page", "size", "totalElements", "totalPages");
        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("page").asInt()).isEqualTo(number);
        assertThat(page.path("size").asInt()).isEqualTo(size);
        assertThat(page.path("totalElements").asLong()).isEqualTo(total);
        assertThat(page.path("totalPages").asInt()).isEqualTo(totalPages);
    }

    private void assertFields(JsonNode node, String... expected) {
        assertThat(node.isObject()).isTrue();
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }

    private void assertSafeId(JsonNode node) {
        assertThat(node.isIntegralNumber()).isTrue();
        assertThat(node.asLong()).isBetween(1L, MAX_SAFE_ID);
    }

    private void assertCanonicalUuid(JsonNode node) {
        assertThat(node.isTextual()).isTrue();
        assertThat(node.asText()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(UUID.fromString(node.asText()).toString()).isEqualTo(node.asText());
    }

    private void assertUtcMillis(JsonNode node) {
        assertThat(node.isTextual()).isTrue();
        assertThat(node.asText()).matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        Instant.parse(node.asText());
    }

    private void assertNoSecrets(String json) {
        assertThat(json).doesNotContainIgnoringCase(
                "fixture-password", "authorization", "refreshToken", "privateKey", "ciphertext",
                "webhookUrl", "urlNonce", "payloadNonce", VAPID.privateKey(),
                SUBSCRIBER.publicKey(), SUBSCRIBER.privateKey(), PUSH_AUTH,
                "https://fcm.googleapis.com/fcm/send/part-c-contract-",
                "https://hooks.slack.com/services/");
        if (userSession != null) assertThat(json).doesNotContain(userSession.token());
        if (otherSession != null) assertThat(json).doesNotContain(otherSession.token());
        if (adminSession != null) assertThat(json).doesNotContain(adminSession.token());
    }

    private UserAccount account(String prefix, UserRole role) {
        return users.saveAndFlush(UserAccount.builder()
                .email(prefix + '-' + UUID.randomUUID() + "@example.test")
                .displayName(prefix)
                .passwordHash(passwords.hash("PartC-contract-password-123"))
                .role(role)
                .enabled(true)
                .build());
    }

    private SessionIdentity issue(UserAccount account, Instant expiresAt) {
        return issue(account, expiresAt,
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""));
    }

    private SessionIdentity issue(UserAccount account, Instant expiresAt, String refreshHash) {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = expiresAt.isBefore(now)
                ? expiresAt.minusSeconds(60)
                : now.minusSeconds(1);
        AuthSession session = sessions.saveAndFlush(AuthSession.builder()
                .id(sessionId)
                .user(account)
                .currentRefreshHash(refreshHash)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .authVersion(account.getAuthVersion())
                .build());
        return new SessionIdentity(session.getId(), accessTokens.issue(account, session.getId()).value());
    }

    private long createTarget(String name) throws Exception {
        JsonNode created = assertJson(request("POST", "/api/v1/databases", adminSession.token(),
                "{\"name\":\"" + name + "\",\"host\":\"127.0.0.1\",\"port\":13306,"
                        + "\"databaseName\":\"contract\",\"username\":\"fixture\","
                        + "\"password\":\"fixture-password\",\"enabled\":true}"), 201);
        assertSafeId(created.path("id"));
        return created.path("id").asLong();
    }

    private String policyBody(long version, int staleAfterSeconds, int cooldownSeconds) {
        return "{\"version\":" + version + ",\"staleAfterSeconds\":" + staleAfterSeconds
                + ",\"notificationCooldownSeconds\":" + cooldownSeconds + ",\"rules\":["
                + "{\"ruleId\":\"CONNECTION_RATIO\",\"metricName\":\"activeConnectionsRatio\","
                + "\"operator\":\"GTE\",\"warningThreshold\":0.80,\"criticalThreshold\":0.90,"
                + "\"fatalThreshold\":0.95,\"sustainSeconds\":15,\"recoverySeconds\":15,"
                + "\"enabled\":true},"
                + "{\"ruleId\":\"SLOW_QUERY_RATE\",\"metricName\":\"slowQueriesPerSecond\","
                + "\"operator\":\"GTE\",\"warningThreshold\":1.0,\"criticalThreshold\":5.0,"
                + "\"fatalThreshold\":null,\"sustainSeconds\":15,\"recoverySeconds\":15,"
                + "\"enabled\":true}]}";
    }

    private String policyBodyWithDuplicateRules(long version) {
        String oneRule = "{\"ruleId\":\"CONNECTION_RATIO\",\"metricName\":\"activeConnectionsRatio\","
                + "\"operator\":\"GTE\",\"warningThreshold\":0.80,\"criticalThreshold\":0.90,"
                + "\"fatalThreshold\":0.95,\"sustainSeconds\":15,\"recoverySeconds\":15,"
                + "\"enabled\":true}";
        return "{\"version\":" + version + ",\"staleAfterSeconds\":60,"
                + "\"notificationCooldownSeconds\":600,\"rules\":[" + oneRule + ',' + oneRule + "]}";
    }

    private String policyBodyMissingFatalThreshold(long version) {
        return policyBody(version, 60, 600).replace("\"fatalThreshold\":0.95,", "");
    }

    private String pushEndpoint(String suffix) {
        return "https://fcm.googleapis.com/fcm/send/part-c-contract-" + suffix;
    }

    private static String offCurvePublicKey() {
        byte[] encoded = new byte[65];
        encoded[0] = 0x04;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
    }

    private String pushBody(String endpoint, Long expirationTime) {
        return pushBodyWithKeys(endpoint, SUBSCRIBER.publicKey(), PUSH_AUTH, expirationTime);
    }

    private String pushBodyWithKeys(String endpoint, String p256dh, String auth) {
        return pushBodyWithKeys(endpoint, p256dh, auth, null);
    }

    private String pushBodyWithKeys(String endpoint, String p256dh, String auth, Long expirationTime) {
        return "{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":"
                + (expirationTime == null ? "null" : expirationTime)
                + ",\"keys\":{\"p256dh\":\"" + p256dh + "\",\"auth\":\"" + auth + "\"}}";
    }

    private String pushBodyWithoutExpiration(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\""
                + SUBSCRIBER.publicKey() + "\",\"auth\":\"" + PUSH_AUTH + "\"}}";
    }

    private String webhookBody(String name, String url, boolean enabled) {
        return "{\"name\":\"" + name + "\",\"provider\":\"SLACK\",\"url\":\""
                + url + "\",\"enabled\":" + enabled + '}';
    }

    private String slackUrl(int index) {
        return "https://hooks.slack.com/services/T00000000/B00000000/contract" + index;
    }

    private HttpResponse<String> request(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = HTTP.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        PartCHttpEvidence.recordHttp(objectMapper, method, path, response);
        return response;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record SessionIdentity(UUID sessionId, String token) {
    }

}
