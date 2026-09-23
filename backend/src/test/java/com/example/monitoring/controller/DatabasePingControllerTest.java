package com.example.monitoring.controller;

import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.service.DatabaseHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DatabasePingController.class)
class DatabasePingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DatabaseHealthService databaseHealthService;

    @Test
    @DisplayName("GET /api/databases/{id}/ping returns 200 OK and ping result DTO when database exists")
    void pingDatabase_success() throws Exception {
        Long dbId = 1L;
        DbPingResponseDto responseDto = DbPingResponseDto.builder()
                .databaseConfigId(dbId)
                .status(TargetDbStatus.UP)
                .version("10.11.2-MariaDB")
                .responseTimeMs(15L)
                .timestamp(LocalDateTime.now())
                .errorMessage(null)
                .build();

        given(databaseHealthService.pingDatabase(dbId)).willReturn(Optional.of(responseDto));

        mockMvc.perform(get("/api/databases/{id}/ping", dbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseConfigId").value(1))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("10.11.2-MariaDB"))
                .andExpect(jsonPath("$.responseTimeMs").value(15));
    }

    @Test
    @DisplayName("GET /api/databases/{id}/ping returns 404 Not Found when database config does not exist")
    void pingDatabase_notFound() throws Exception {
        Long dbId = 999L;
        given(databaseHealthService.pingDatabase(dbId)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/databases/{id}/ping", dbId))
                .andExpect(status().isNotFound());
    }
}
