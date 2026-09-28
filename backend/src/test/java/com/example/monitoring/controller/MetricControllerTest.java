package com.example.monitoring.controller;

import com.example.monitoring.domain.CollectionStatus;
import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.service.MetricService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MetricController.class)
class MetricControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MetricService metricService;

    @Test
    @DisplayName("GET /api/v1/metrics/{dbId}/latest returns latest metric snapshot")
    void getLatestMetric_returnsSnapshot() throws Exception {
        Long dbId = 1L;
        MetricResponseDto dto = MetricResponseDto.builder()
                .id(100L)
                .databaseConfigId(dbId)
                .timestamp(LocalDateTime.now())
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
                .timestamp(LocalDateTime.now())
                .activeConnections(10L)
                .collectionStatus(CollectionStatus.SUCCESS)
                .build();

        given(metricService.getRecentMetrics(dbId, 50)).willReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/metrics/{dbId}/recent", dbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].databaseConfigId").value(dbId))
                .andExpect(jsonPath("$[0].activeConnections").value(10));
    }
}
