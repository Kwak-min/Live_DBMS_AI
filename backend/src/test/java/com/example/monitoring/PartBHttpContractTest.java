package com.example.monitoring;

import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.auth.service.AccessTokenService;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.repository.DatabaseConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies generated OpenAPI and a real HTTP error without external infrastructure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.collector.enabled=false",
        "app.metrics.retention-cleanup-enabled=false",
        "app.part-b.retention-cleanup-enabled=false",
        "app.outbox.publisher-enabled=false",
        "app.database-security.verify-on-startup=false"
})
@ActiveProfiles("local")
class PartBHttpContractTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_KEYS = "{\"1\":\"" + KEY + "\"}";
    private static final EmbeddedPostgres POSTGRES;

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
    }

    @AfterAll
    static void closePostgres() throws Exception {
        POSTGRES.close();
        System.clearProperty("DB_CONFIG_ACTIVE_KEY_VERSION");
        System.clearProperty("DB_CONFIG_ENCRYPTION_KEYS");
        System.clearProperty("LEGACY_TIME_ZONE");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private DatabaseConfigRepository databaseConfigRepository;

    @Test
    void generatedOpenApiIncludesPartBEndpointsAndExcludesResponseSecrets() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode api = objectMapper.readTree(response.body());

        assertThat(api.path("components").path("securitySchemes").path("bearerAuth")
                .path("scheme").asText()).isEqualTo("bearer");
        JsonNode paths = api.path("paths");
        assertThat(paths.path("/api/v1/auth/login").path("post").isObject()).isTrue();
        assertThat(paths.path("/api/v1/auth/me").path("get").isObject()).isTrue();
        assertThat(paths.path("/api/v1/databases").path("post").isObject()).isTrue();
        assertThat(paths.path("/api/v1/databases/{id}/ping").path("post").isObject()).isTrue();
        assertThat(paths.path("/api/v1/audit-logs").path("get").isObject()).isTrue();
        assertThat(paths.path("/api/v1/access-logs").path("get").isObject()).isTrue();
        assertThat(paths.path("/api/v1/databases").path("post").path("responses").has("201")).isTrue();
        assertThat(paths.path("/api/v1/databases/{id}/ping").path("post")
                .path("responses").has("200")).isTrue();
        JsonNode databaseUnauthorized = paths.path("/api/v1/databases").path("get")
                .path("responses").path("401").path("content").path("application/json")
                .path("schema");
        assertThat(databaseUnauthorized.path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiErrorResponse");
        assertThat(api.path("components").path("schemas").path("ApiErrorResponse")
                .path("properties").has("fieldErrors")).isTrue();
        int errorResponses = 0;
        var pathEntries = paths.fields();
        while (pathEntries.hasNext()) {
            var pathEntry = pathEntries.next();
            String path = pathEntry.getKey();
            if (!(path.startsWith("/api/v1/auth/") || path.equals("/api/v1/users")
                    || path.startsWith("/api/v1/users/") || path.equals("/api/v1/databases")
                    || path.startsWith("/api/v1/databases/") || path.equals("/api/v1/audit-logs")
                    || path.equals("/api/v1/access-logs"))) continue;
            var operations = pathEntry.getValue().fields();
            while (operations.hasNext()) {
                var operation = operations.next();
                JsonNode responses = operation.getValue().path("responses");
                var responseEntries = responses.fields();
                while (responseEntries.hasNext()) {
                    var entry = responseEntries.next();
                    if (!entry.getKey().matches("[45][0-9][0-9]")) continue;
                    errorResponses++;
                    assertThat(entry.getValue().path("content").path("application/json")
                            .path("schema").path("$ref").asText())
                            .as("%s %s %s", operation.getKey(), path, entry.getKey())
                            .isEqualTo("#/components/schemas/ApiErrorResponse");
                }
            }
        }
        assertThat(errorResponses).isGreaterThan(0);

        JsonNode databaseResponse = api.path("components").path("schemas")
                .path("DatabaseResponse").path("properties");
        assertThat(databaseResponse.isObject()).isTrue();
        assertThat(databaseResponse.has("configVersion")).isTrue();
        assertThat(databaseResponse.has("username")).isFalse();
        assertThat(databaseResponse.has("password")).isFalse();
        assertThat(databaseResponse.has("usernameCiphertext")).isFalse();
        assertThat(databaseResponse.has("passwordCiphertext")).isFalse();

        Path export = Path.of("build", "reports", "part-b-openapi.json");
        Files.createDirectories(export.getParent());
        Files.writeString(export, response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void unauthenticatedDatabaseRequestUsesDocumentedErrorEnvelope() throws Exception {
        HttpResponse<String> response = get("/api/v1/databases");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        UUID.fromString(requestId);

        JsonNode error = objectMapper.readTree(response.body());
        assertThat(error.path("code").asText()).isEqualTo("AUTH_REQUIRED");
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("requestId").asText()).isEqualTo(requestId);
        assertThat(error.path("fieldErrors").isArray()).isTrue();
        assertThat(error.path("fieldErrors").isEmpty()).isTrue();
    }

    @Test
    void authenticatedReadResponsesMatchDocumentedFields() throws Exception {
        UserAccount user = userAccountRepository.save(UserAccount.builder()
                .email("contract-" + UUID.randomUUID() + "@example.test")
                .displayName("Contract test user")
                .passwordHash("unused-test-hash")
                .build());
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        authSessionRepository.save(AuthSession.builder()
                .id(sessionId)
                .user(user)
                .currentRefreshHash(UUID.randomUUID().toString().replace("-", ""))
                .createdAt(now)
                .expiresAt(now.plusSeconds(3600))
                .authVersion(user.getAuthVersion())
                .build());
        String token = accessTokenService.issue(user, sessionId).value();

        HttpResponse<String> meResponse = get("/api/v1/auth/me", token);
        assertThat(meResponse.statusCode()).isEqualTo(200);
        JsonNode me = objectMapper.readTree(meResponse.body());
        assertThat(me.path("id").asLong()).isEqualTo(user.getId());
        assertThat(me.path("email").asText()).isEqualTo(user.getEmail());
        assertThat(me.has("passwordHash")).isFalse();

        HttpResponse<String> databasesResponse = get("/api/v1/databases", token);
        assertThat(databasesResponse.statusCode()).isEqualTo(200);
        JsonNode databases = objectMapper.readTree(databasesResponse.body());
        assertThat(databases.path("items").isArray()).isTrue();
        assertThat(databases.path("items").isEmpty()).isTrue();
        assertThat(databases.path("page").asInt()).isEqualTo(0);
        assertThat(databases.path("size").asInt()).isEqualTo(20);
        assertThat(databases.path("totalElements").asLong()).isZero();
        assertThat(databases.path("totalPages").asInt()).isZero();
    }

    @Test
    void databaseListTimestampsUseUtcWithExactlyThreeFractionalDigits() throws Exception {
        UserAccount user = userAccountRepository.save(UserAccount.builder()
                .email("database-time-" + UUID.randomUUID() + "@example.test")
                .displayName("Database time test")
                .passwordHash("unused-test-hash")
                .build());
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        authSessionRepository.save(AuthSession.builder().id(sessionId).user(user)
                .currentRefreshHash(UUID.randomUUID().toString().replace("-", ""))
                .createdAt(now).expiresAt(now.plusSeconds(3600))
                .authVersion(user.getAuthVersion()).build());
        String token = accessTokenService.issue(user, sessionId).value();
        DatabaseConfig config = databaseConfigRepository.saveAndFlush(DatabaseConfig.builder()
                .name("timestamp-test").host("127.0.0.1").port(13306).enabled(true).build());
        try {
            HttpResponse<String> response = get("/api/v1/databases", token);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode items = objectMapper.readTree(response.body()).path("items");
            JsonNode item = null;
            for (JsonNode candidate : items) {
                if (candidate.path("id").asLong() == config.getId()) item = candidate;
            }
            assertThat(item).isNotNull();
            assertThat(item.path("createdAt").asText())
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
            assertThat(item.path("updatedAt").asText())
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        } finally {
            databaseConfigRepository.deleteById(config.getId());
        }
    }

    @Test
    void crossOriginRequestsGetNoCorsGrantEvenForLocalDevelopmentPorts() throws Exception {
        // 운영은 단일 origin이고 로컬은 Vite proxy로 같은 origin을 쓴다(integration-security.md 3·4절).
        // 개발용 포트라도 credential CORS를 열면 Origin 기반 CSRF 방어가 넓어진다.
        for (String origin : new String[]{"http://localhost:3000", "http://127.0.0.1:5173", "https://evil.example"}) {
            HttpRequest preflight = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/databases"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization")
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(preflight, HttpResponse.BodyHandlers.ofString());

            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).as(origin).isEmpty();
            assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).as(origin).isEmpty();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, null);
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
