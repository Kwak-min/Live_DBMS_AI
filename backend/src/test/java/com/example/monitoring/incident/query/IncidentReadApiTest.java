package com.example.monitoring.incident.query;

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

import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentReadApiTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00.000Z");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AT_START = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID AT_END = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID DELETED_HISTORY = UUID.fromString("00000000-0000-0000-0000-000000000014");

    private final ObjectMapper objectMapper = utcObjectMapper();
    private Connection connection;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private IncidentQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
        connection.setAutoCommit(false);
        String schema = "incident_read_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
        }
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        createSchema();
        seedRows();

        IncidentQueryRepository repository = new IncidentQueryRepository(jdbc);
        service = new IncidentQueryService(
                repository,
                new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC)));
        mockMvc = MockMvcBuilders.standaloneSetup(new IncidentQueryController(service))
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
    void filtersPagesAndOrdersExactIncidentSnapshotsFromPostgres() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/incidents")
                        .param("databaseConfigId", "12")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].incidentId").value(FIRST.toString()))
                .andExpect(jsonPath("$.items[1].incidentId").value(SECOND.toString()))
                .andExpect(jsonPath("$.items[2].incidentId").value(AT_START.toString()))
                .andReturn();

        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(fieldNames(page)).containsExactlyInAnyOrder(
                "items", "page", "size", "totalElements", "totalPages");
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
        JsonNode first = page.path("items").get(0);
        assertThat(fieldNames(first)).containsExactlyInAnyOrder(
                "incidentId", "databaseConfigId", "databaseName", "ruleId", "ruleType", "severity",
                "status", "openedAt", "lastObservedAt", "resolvedAt", "resolutionReason", "metricName",
                "metricValue", "thresholdValue", "sourceMetricId", "message", "incidentVersion");
        assertThat(first.has("sourceEventId")).isFalse();

        mockMvc.perform(get("/api/v1/incidents")
                        .param("databaseConfigId", "12")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].incidentId").value(AT_START.toString()));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("databaseConfigId", "12")
                        .param("severity", "CRITICAL")
                        .param("status", "OPEN")
                        .param("start", "2026-08-29T12:00:00.000Z")
                        .param("end", "2026-09-28T12:00:00.000Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].incidentId").value(SECOND.toString()));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}", FIRST.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionReason").value("RECOVERED"))
                .andExpect(jsonPath("$.openedAt").value("2026-09-28T11:00:00.000Z"));
    }

    @Test
    void keepsDeletedHistoryQueryableAndRejectsMalformedWindows() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").param("databaseConfigId", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].incidentId").value(DELETED_HISTORY.toString()));

        mockMvc.perform(get("/api/v1/incidents").param("databaseConfigId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("start", "2026-09-28T00:00:00.000Z"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("end"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("start", "2026-08-29T11:59:59.999Z")
                        .param("end", "2026-09-28T12:00:00.000Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("start"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("OUT_OF_RANGE"));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}",
                        "00000000-0000-0000-0000-000000000099"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }

    @Test
    void methodSecurityAllowsUserAndAdminButRejectsGuestAndAnonymous() {
        IncidentQueryController secured = securedController(service);
        try {
            authenticate("ROLE_USER");
            assertThat(secured.get(FIRST.toString()).getStatusCode().is2xxSuccessful()).isTrue();
            authenticate("ROLE_ADMIN");
            assertThat(secured.get(FIRST.toString()).getStatusCode().is2xxSuccessful()).isTrue();
            authenticate("ROLE_GUEST");
            assertThatThrownBy(() -> secured.get(FIRST.toString())).isInstanceOf(AccessDeniedException.class);
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> secured.get(FIRST.toString()))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE incidents (
                    incident_id UUID PRIMARY KEY,
                    database_config_id BIGINT NOT NULL,
                    database_name VARCHAR(100) NOT NULL,
                    rule_id VARCHAR(64) NOT NULL,
                    rule_type VARCHAR(40) NOT NULL,
                    severity VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    opened_at TIMESTAMPTZ NOT NULL,
                    last_observed_at TIMESTAMPTZ NOT NULL,
                    resolved_at TIMESTAMPTZ,
                    resolution_reason VARCHAR(32),
                    metric_name VARCHAR(100) NOT NULL,
                    metric_value NUMERIC,
                    threshold_value NUMERIC,
                    source_metric_id BIGINT,
                    source_event_id UUID,
                    message TEXT NOT NULL,
                    incident_version BIGINT NOT NULL
                )
                """);
    }

    private void seedRows() {
        seed(FIRST, 12L, "WARNING", "RESOLVED", NOW.minusSeconds(3600), NOW.minusSeconds(3500),
                NOW.minusSeconds(3400), "RECOVERED", "0.81", "0.80", 40L, 2L);
        seed(SECOND, 12L, "CRITICAL", "OPEN", NOW.minusSeconds(3600), NOW.minusSeconds(5),
                null, null, "0.91", "0.90", 41L, 3L);
        seed(AT_START, 12L, "WARNING", "OPEN", NOW.minusSeconds(86_400), NOW.minusSeconds(86_390),
                null, null, "0.82", "0.80", null, 1L);
        seed(AT_END, 12L, "FATAL", "OPEN", NOW, NOW,
                null, null, "0.99", "0.95", 42L, 1L);
        seed(DELETED_HISTORY, 14L, "CRITICAL", "OPEN", NOW.minusSeconds(7200), NOW.minusSeconds(7100),
                null, null, "0.93", "0.90", null, 1L);
    }

    private void seed(UUID id, long databaseConfigId, String severity, String status,
                      Instant openedAt, Instant lastObservedAt, Instant resolvedAt, String resolutionReason,
                      String metricValue, String thresholdValue, Long sourceMetricId, long version) {
        jdbc.update("""
                INSERT INTO incidents (
                    incident_id, database_config_id, database_name, rule_id, rule_type, severity, status,
                    opened_at, last_observed_at, resolved_at, resolution_reason, metric_name, metric_value,
                    threshold_value, source_metric_id, source_event_id, message, incident_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, databaseConfigId, databaseConfigId == 14 ? "삭제된 대상" : "운영 MariaDB",
                "CONNECTION_RATIO", "CONNECTION_RATIO_EXCEEDED", severity, status,
                timestamp(openedAt), timestamp(lastObservedAt), timestamp(resolvedAt), resolutionReason,
                "activeConnectionsRatio", new BigDecimal(metricValue), new BigDecimal(thresholdValue),
                sourceMetricId, UUID.randomUUID(), "연결 사용 비율 임계치 초과", version);
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

    private static IncidentQueryController securedController(IncidentQueryService service) {
        ProxyFactory proxy = new ProxyFactory(new IncidentQueryController(service));
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return (IncidentQueryController) proxy.getProxy();
    }

    private static void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-user", "unused", java.util.List.of(new SimpleGrantedAuthority(authority))));
    }
}
