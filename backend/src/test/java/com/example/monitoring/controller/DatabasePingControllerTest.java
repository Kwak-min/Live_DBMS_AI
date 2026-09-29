package com.example.monitoring.controller;

import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.service.DatabaseHealthService;
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
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DatabasePingController.class)
@AutoConfigureMockMvc(addFilters = false)
class DatabasePingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DatabaseHealthService databaseHealthService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private AuthService authService;

    @MockBean
    private ApiSecurityErrorWriter apiSecurityErrorWriter;

    @MockBean private CsrfTokenService csrfTokenService;
    @MockBean private RequestOriginValidator requestOriginValidator;

    @Test
    @DisplayName("POST /api/v1/databases/{id}/ping returns 200 OK and ping result DTO when database exists")
    void pingDatabase_success() throws Exception {
        Long dbId = 1L;
        DbPingResponseDto responseDto = DbPingResponseDto.builder()
                .databaseConfigId(dbId)
                .status(TargetDbStatus.UP)
                .version("10.11.2-MariaDB")
                .responseTimeMs(15L)
                .timestamp(Instant.now())
                .errorMessage(null)
                .build();

        given(databaseHealthService.pingDatabase(dbId)).willReturn(Optional.of(responseDto));

        mockMvc.perform(post("/api/v1/databases/{id}/ping", dbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseConfigId").value(1))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("10.11.2-MariaDB"))
                .andExpect(jsonPath("$.responseTimeMs").value(15));
    }

    @Test
    @DisplayName("POST /api/v1/databases/{id}/ping returns 404 Not Found when database config does not exist")
    void pingDatabase_notFound() throws Exception {
        Long dbId = 999L;
        given(databaseHealthService.pingDatabase(dbId)).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/databases/{id}/ping", dbId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DATABASE_NOT_FOUND"));
    }
}
