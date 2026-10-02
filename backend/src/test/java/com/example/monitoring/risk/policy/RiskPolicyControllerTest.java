package com.example.monitoring.risk.policy;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiExceptionHandler;
import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.common.web.RequestIdFilter;
import com.example.monitoring.risk.contract.MonitoringContracts;
import com.example.monitoring.risk.contract.RiskPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskPolicyControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:34:56.000Z");
    private final RiskPolicyService service = mock(RiskPolicyService.class);
    private final ObjectMapper objectMapper = utcObjectMapper();
    private final RiskPolicy policy = new RiskPolicy(
            12L, 6L, 120, 600, MonitoringContracts.defaultRules(), NOW);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(service.get("12")).thenReturn(policy);
        when(service.update(eq("12"), any(PolicyWrite.class))).thenReturn(policy);
        mockMvc = MockMvcBuilders.standaloneSetup(new RiskPolicyController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void getAndPutReturnExactNoStorePolicyFields() throws Exception {
        MvcResult getResult = mockMvc.perform(get("/api/v1/databases/{id}/risk-policy", "12"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.databaseConfigId").value(12))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.rules.length()").value(2))
                .andReturn();
        JsonNode response = objectMapper.readTree(getResult.getResponse().getContentAsByteArray());
        assertThat(fieldNames(response)).containsExactlyInAnyOrder(
                "databaseConfigId", "version", "staleAfterSeconds",
                "notificationCooldownSeconds", "rules", "updatedAt");
        assertThat(fieldNames(response.path("rules").get(0))).containsExactlyInAnyOrder(
                "ruleId", "metricName", "operator", "warningThreshold", "criticalThreshold",
                "fatalThreshold", "sustainSeconds", "recoverySeconds", "enabled");
        assertThat(response.path("updatedAt").asText()).isEqualTo("2026-09-28T12:34:56.000Z");

        mockMvc.perform(put("/api/v1/databases/{id}/risk-policy", "12")
                        .contentType("application/json")
                        .content(validWriteJson()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.version").value(6));
    }

    @Test
    void staleVersionUsesCommonConflictEnvelope() throws Exception {
        when(service.update(eq("12"), any(PolicyWrite.class))).thenThrow(new ApiException(
                HttpStatus.CONFLICT,
                "POLICY_VERSION_CONFLICT",
                "정책 버전이 일치하지 않습니다."));

        mockMvc.perform(put("/api/v1/databases/{id}/risk-policy", "12")
                        .contentType("application/json")
                        .content(validWriteJson()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void requiresTheNullableFatalThresholdMemberToBePresent() throws Exception {
        String missingFatalThreshold = validWriteJson().replace(
                "\"fatalThreshold\": null,\n",
                "");

        mockMvc.perform(put("/api/v1/databases/{id}/risk-policy", "12")
                        .contentType("application/json")
                        .content(missingFatalThreshold))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsJsonScalarTypeCoercion() throws Exception {
        for (String invalid : List.of(
                validWriteJson().replace("\"version\": 5", "\"version\": 5.0"),
                validWriteJson().replace("\"sustainSeconds\": 15", "\"sustainSeconds\": 15.5"),
                validWriteJson().replace("\"ruleId\": \"CONNECTION_RATIO\"", "\"ruleId\": 0"),
                validWriteJson().replace("\"warningThreshold\": 0.80", "\"warningThreshold\": \"0.80\""),
                validWriteJson().replace("\"enabled\": true", "\"enabled\": \"true\""))) {
            mockMvc.perform(put("/api/v1/databases/{id}/risk-policy", "12")
                            .contentType("application/json")
                            .content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void methodSecurityAllowsReadsForUserAndAdminButWritesOnlyForAdmin() {
        RiskPolicyController secured = securedController(service);
        try {
            authenticate("ROLE_USER");
            assertThat(secured.get("12").getStatusCode().is2xxSuccessful()).isTrue();
            assertThatThrownBy(() -> secured.update("12", write()))
                    .isInstanceOf(AccessDeniedException.class);

            authenticate("ROLE_ADMIN");
            assertThat(secured.get("12").getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(secured.update("12", write()).getStatusCode().is2xxSuccessful()).isTrue();

            authenticate("ROLE_GUEST");
            assertThatThrownBy(() -> secured.get("12")).isInstanceOf(AccessDeniedException.class);
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> secured.get("12"))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private PolicyWrite write() {
        return new PolicyWrite(
                5L,
                120,
                600,
                MonitoringContracts.defaultRules().stream().map(PolicyRuleWrite::from).toList());
    }

    private String validWriteJson() {
        return """
                {
                  "version": 5,
                  "staleAfterSeconds": 120,
                  "notificationCooldownSeconds": 600,
                  "rules": [
                    {
                      "ruleId": "CONNECTION_RATIO",
                      "metricName": "activeConnectionsRatio",
                      "operator": "GTE",
                      "warningThreshold": 0.80,
                      "criticalThreshold": 0.90,
                      "fatalThreshold": 0.95,
                      "sustainSeconds": 15,
                      "recoverySeconds": 15,
                      "enabled": true
                    },
                    {
                      "ruleId": "SLOW_QUERY_RATE",
                      "metricName": "slowQueriesPerSecond",
                      "operator": "GTE",
                      "warningThreshold": 1.0,
                      "criticalThreshold": 5.0,
                      "fatalThreshold": null,
                      "sustainSeconds": 15,
                      "recoverySeconds": 15,
                      "enabled": true
                    }
                  ]
                }
                """;
    }

    private static Set<String> fieldNames(JsonNode value) {
        Set<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ObjectMapper utcObjectMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new UtcInstantJacksonConfig().utcInstantCustomizer().customize(builder);
        return builder.build();
    }

    private static RiskPolicyController securedController(RiskPolicyService service) {
        ProxyFactory proxy = new ProxyFactory(new RiskPolicyController(service));
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return (RiskPolicyController) proxy.getProxy();
    }

    private static void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-user", "unused", List.of(new SimpleGrantedAuthority(authority))));
    }
}
