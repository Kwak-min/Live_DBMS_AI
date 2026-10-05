package com.example.monitoring.status;

import com.example.monitoring.common.api.ApiExceptionHandler;
import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.web.RequestIdFilter;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatusReadApiTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:34:56.789Z");
    private static final UUID INCIDENT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INCIDENT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ObjectMapper objectMapper = utcObjectMapper();
    private Connection connection;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private StatusQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
        connection.setAutoCommit(false);
        String schema = "status_read_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
        }
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        createSchema();
        seedRows();

        StatusQueryRepository repository = new StatusQueryRepository(jdbc);
        service = new StatusQueryService(
                repository,
                new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC)));
        mockMvc = MockMvcBuilders.standaloneSetup(new StatusController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.rollback();
        connection.close();
    }

    @Test
    void returnsExactCurrentAndPausedStatusSnapshotsFromPostgres() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/databases/{id}/status", "12"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.databaseConfigId").value(12))
                .andExpect(jsonPath("$.configVersion").value(3))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.connectionStatus").value("UP"))
                .andExpect(jsonPath("$.dataFreshness").value("FRESH"))
                .andExpect(jsonPath("$.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.openIncidentIds[0]").value(INCIDENT_A.toString()))
                .andExpect(jsonPath("$.openIncidentIds[1]").value(INCIDENT_B.toString()))
                .andReturn();

        JsonNode status = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(fieldNames(status)).containsExactlyInAnyOrder(
                "databaseConfigId", "configVersion", "deleted", "enabled", "connectionStatus",
                "dataFreshness", "riskLevel", "lastAttemptAt", "lastSuccessAt", "latestMetricId",
                "openIncidentIds", "stateVersion", "updatedAt");
        assertThat(status.has("activationAt")).isFalse();
        assertThat(status.path("updatedAt").asText()).isEqualTo("2026-09-28T12:34:56.789Z");

        mockMvc.perform(get("/api/v1/databases/{id}/status", "13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.dataFreshness").value("PAUSED"))
                .andExpect(jsonPath("$.riskLevel").value(nullValue()))
                .andExpect(jsonPath("$.openIncidentIds").isEmpty());
    }

    @Test
    void rejectsDeletedMissingAndUnsafeTargetsWithCommonErrors() throws Exception {
        for (String id : new String[]{"14", "15"}) {
            mockMvc.perform(get("/api/v1/databases/{id}/status", id))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(jsonPath("$.code").value("DATABASE_NOT_FOUND"));
        }

        mockMvc.perform(get("/api/v1/databases/{id}/status", "9007199254740992"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("OUT_OF_RANGE"));
    }

    @Test
    void methodSecurityAllowsUserAndAdminButRejectsGuestAndAnonymous() {
        StatusController secured = securedController(service);
        try {
            authenticate("ROLE_USER");
            assertThat(secured.get("12").getStatusCode().is2xxSuccessful()).isTrue();
            authenticate("ROLE_ADMIN");
            assertThat(secured.get("12").getStatusCode().is2xxSuccessful()).isTrue();
            authenticate("ROLE_GUEST");
            assertThatThrownBy(() -> secured.get("12")).isInstanceOf(AccessDeniedException.class);
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> secured.get("12"))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE database_configs (id BIGINT PRIMARY KEY, deleted_at TIMESTAMPTZ)");
        jdbc.execute("""
                CREATE TABLE monitoring_states (
                    database_config_id BIGINT PRIMARY KEY,
                    config_version BIGINT NOT NULL,
                    state_version BIGINT NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    deleted BOOLEAN NOT NULL,
                    connection_status VARCHAR(16) NOT NULL,
                    data_freshness VARCHAR(16) NOT NULL,
                    risk_level VARCHAR(16),
                    activation_at TIMESTAMPTZ,
                    last_attempt_at TIMESTAMPTZ,
                    last_success_at TIMESTAMPTZ,
                    latest_metric_id BIGINT,
                    updated_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE incidents (
                    incident_id UUID PRIMARY KEY,
                    database_config_id BIGINT NOT NULL,
                    status VARCHAR(16) NOT NULL
                )
                """);
    }

    private void seedRows() {
        jdbc.update("INSERT INTO database_configs (id, deleted_at) VALUES (12, NULL), (13, NULL), (14, ?), (15, NULL)",
                Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO monitoring_states (
                    database_config_id, config_version, state_version, enabled, deleted,
                    connection_status, data_freshness, risk_level, activation_at,
                    last_attempt_at, last_success_at, latest_metric_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, 12L, 3L, 7L, true, false, "UP", "FRESH", "CRITICAL",
                timestamp(NOW.minusSeconds(3600)), timestamp(NOW.minusSeconds(5)),
                timestamp(NOW.minusSeconds(10)), 41L, timestamp(NOW));
        jdbc.update("""
                INSERT INTO monitoring_states (
                    database_config_id, config_version, state_version, enabled, deleted,
                    connection_status, data_freshness, risk_level, activation_at,
                    last_attempt_at, last_success_at, latest_metric_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, 13L, 1L, 2L, false, false, "UNKNOWN", "PAUSED", null,
                null, null, null, null, timestamp(NOW));
        jdbc.update("""
                INSERT INTO monitoring_states (
                    database_config_id, config_version, state_version, enabled, deleted,
                    connection_status, data_freshness, risk_level, activation_at,
                    last_attempt_at, last_success_at, latest_metric_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, 14L, 2L, 4L, false, true, "UNKNOWN", "PAUSED", null,
                null, null, null, null, timestamp(NOW));
        jdbc.update("INSERT INTO incidents (incident_id, database_config_id, status) VALUES (?, 12, 'OPEN')", INCIDENT_B);
        jdbc.update("INSERT INTO incidents (incident_id, database_config_id, status) VALUES (?, 12, 'RESOLVED')",
                UUID.fromString("00000000-0000-0000-0000-000000000003"));
        jdbc.update("INSERT INTO incidents (incident_id, database_config_id, status) VALUES (?, 12, 'OPEN')", INCIDENT_A);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static ObjectMapper utcObjectMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new UtcInstantJacksonConfig().utcInstantCustomizer().customize(builder);
        return builder.build();
    }

    private static Set<String> fieldNames(JsonNode value) {
        Set<String> names = new java.util.LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static StatusController securedController(StatusQueryService service) {
        ProxyFactory proxy = new ProxyFactory(new StatusController(service));
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return (StatusController) proxy.getProxy();
    }

    private static void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-user", "unused", java.util.List.of(new SimpleGrantedAuthority(authority))));
    }
}
