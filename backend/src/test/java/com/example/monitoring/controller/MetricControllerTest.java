package com.example.monitoring.controller;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.service.MetricService;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.auth.service.CsrfTokenService;
import com.example.monitoring.auth.service.RequestOriginValidator;
import com.example.monitoring.auth.web.ApiSecurityErrorWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MetricController.class)
@AutoConfigureMockMvc(addFilters = false)
class MetricControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MetricService metricService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private AuthService authService;

    @MockBean
    private ApiSecurityErrorWriter apiSecurityErrorWriter;

    @MockBean private CsrfTokenService csrfTokenService;
    @MockBean private RequestOriginValidator requestOriginValidator;

    @Test
    @DisplayName("GET /api/v1/metrics/{dbId}/latest returns latest metric snapshot")
    void getLatestMetric_returnsSnapshot() throws Exception {
        Long dbId = 1L;
        MetricResponseDto dto = MetricResponseDto.builder()
                .id(100L)
                .databaseConfigId(dbId)
                .timestamp(Instant.now())
                .activeConnections(15L)
                .maxConnections(100L)
                .qps(250.5)
                .slowQueries(0L)
                .threadsRunning(3L)
                .responseTimeMs(5L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .build();

        given(metricService.getLatestMetric(dbId)).willReturn(Optional.of(dto));

        mockMvc.perform(get("/api/v1/metrics/{dbId}/latest", dbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseConfigId").value(dbId))
                .andExpect(jsonPath("$.activeConnections").value(15))
                .andExpect(jsonPath("$.qps").value(250.5))
                .andExpect(jsonPath("$.collectionStatus").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /api/v1/metrics/{dbId}/recent returns recent metrics list")
    void getRecentMetrics_returnsList() throws Exception {
        Long dbId = 1L;
        MetricResponseDto dto = MetricResponseDto.builder()
                .id(101L)
                .databaseConfigId(dbId)
                .timestamp(Instant.now())
                .activeConnections(10L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .build();

        given(metricService.getRecentMetrics(dbId, null)).willReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/metrics/{dbId}/recent", dbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].databaseConfigId").value(dbId))
                .andExpect(jsonPath("$[0].activeConnections").value(10));
    }

    @Test
    @DisplayName("GET /api/v1/metrics/{dbId}/latest returns 204 without body when no snapshot exists yet")
    void getLatestMetric_noSnapshot_returns204() throws Exception {
        given(metricService.getLatestMetric(1L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/metrics/{dbId}/latest", 1L))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("GET /api/v1/metrics/{dbId}/history passes the raw UTC range and limit to the service")
    void getHistory_passesRange() throws Exception {
        given(metricService.getMetricHistory(1L, "2026-09-28T03:00:00.000Z", "2026-09-28T04:00:00.000Z"))
                .willReturn(List.of());
        given(metricService.getRecentMetrics(1L, 10)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/metrics/{dbId}/history", 1L)
                        .param("start", "2026-09-28T03:00:00.000Z")
                        .param("end", "2026-09-28T04:00:00.000Z"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mockMvc.perform(get("/api/v1/metrics/{dbId}/recent", 1L).param("limit", "10"))
                .andExpect(status().isOk());
    }
}
