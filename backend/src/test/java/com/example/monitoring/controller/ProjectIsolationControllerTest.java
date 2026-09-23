package com.example.monitoring.controller;

import com.example.monitoring.domain.BlockedReason;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.BlockStatusResponseDto;
import com.example.monitoring.service.ProjectIsolationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProjectIsolationController.class)
@DisplayName("ProjectIsolationController 슬라이스 테스트")
class ProjectIsolationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProjectIsolationService projectIsolationService;

    private static final String BASE_URL = "/api/v1/projects";

    // ----------------------------------------------------------------
    // GET /block-status
    // ----------------------------------------------------------------

    @Test
    @DisplayName("GET /{id}/block-status - 차단되지 않은 DB 상태 조회 → 200")
    void getBlockStatus_active_returns200() throws Exception {
        BlockStatusResponseDto dto = BlockStatusResponseDto.builder()
                .databaseConfigId(1L)
                .databaseName("test-db")
                .status(TargetDbStatus.UP)
                .enabled(true)
                .activeBlock(null)
                .build();

        when(projectIsolationService.getBlockStatus(1L)).thenReturn(dto);

        mockMvc.perform(get(BASE_URL + "/1/block-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseConfigId").value(1))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.activeBlock").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}/block-status - 차단된 DB 상태 조회 → 200 with activeBlock")
    void getBlockStatus_blocked_returns200WithActiveBlock() throws Exception {
        BlockStatusResponseDto.ActiveBlockInfo blockInfo = BlockStatusResponseDto.ActiveBlockInfo.builder()
                .blockRecordId(5L)
                .severity("FATAL")
                .blockType(BlockedReason.BlockType.AUTO)
                .reason("[AUTO] Connection failed")
                .blockedBy("SYSTEM")
                .blockedAt(LocalDateTime.of(2026, 9, 22, 10, 0))
                .build();

        BlockStatusResponseDto dto = BlockStatusResponseDto.builder()
                .databaseConfigId(2L)
                .databaseName("blocked-db")
                .status(TargetDbStatus.BLOCKED)
                .enabled(false)
                .activeBlock(blockInfo)
                .build();

        when(projectIsolationService.getBlockStatus(2L)).thenReturn(dto);

        mockMvc.perform(get(BASE_URL + "/2/block-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.activeBlock.severity").value("FATAL"))
                .andExpect(jsonPath("$.activeBlock.blockType").value("AUTO"));
    }

    @Test
    @DisplayName("GET /{id}/block-status - 존재하지 않는 ID → 404")
    void getBlockStatus_notFound_returns404() throws Exception {
        when(projectIsolationService.getBlockStatus(999L))
                .thenThrow(new EntityNotFoundException("DatabaseConfig not found: id=999"));

        mockMvc.perform(get(BASE_URL + "/999/block-status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // ----------------------------------------------------------------
    // GET /blocked
    // ----------------------------------------------------------------

    @Test
    @DisplayName("GET /blocked - 차단된 DB 목록 반환 → 200")
    void getAllBlocked_returns200() throws Exception {
        BlockStatusResponseDto dto = BlockStatusResponseDto.builder()
                .databaseConfigId(2L)
                .databaseName("blocked-db")
                .status(TargetDbStatus.BLOCKED)
                .enabled(false)
                .build();

        when(projectIsolationService.getAllBlockedProjects()).thenReturn(List.of(dto));

        mockMvc.perform(get(BASE_URL + "/blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].databaseConfigId").value(2))
                .andExpect(jsonPath("$[0].status").value("BLOCKED"));
    }

    // ----------------------------------------------------------------
    // POST /block
    // ----------------------------------------------------------------

    @Test
    @DisplayName("POST /{id}/block - 수동 차단 성공 → 200")
    void block_success_returns200() throws Exception {
        BlockStatusResponseDto dto = BlockStatusResponseDto.builder()
                .databaseConfigId(1L)
                .databaseName("test-db")
                .status(TargetDbStatus.BLOCKED)
                .enabled(false)
                .build();

        when(projectIsolationService.manualBlock(eq(1L), anyString(), anyString())).thenReturn(dto);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("reason", "정기 점검", "approvedBy", "admin"));

        mockMvc.perform(post(BASE_URL + "/1/block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    @Test
    @DisplayName("POST /{id}/block - 이미 차단된 DB → 409 Conflict")
    void block_alreadyBlocked_returns409() throws Exception {
        when(projectIsolationService.manualBlock(eq(2L), anyString(), anyString()))
                .thenThrow(new IllegalStateException("DB [id=2] is already BLOCKED."));

        String requestBody = objectMapper.writeValueAsString(
                Map.of("reason", "점검", "approvedBy", "admin"));

        mockMvc.perform(post(BASE_URL + "/2/block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("ManualBlockRequestDto - reason 필드에 @NotBlank 어노테이션이 존재한다")
    void blockRequestDto_hasNotBlankOnReason() throws NoSuchFieldException {
        // DTO 필드에 @NotBlank 선언 여부를 리플렉션으로 검증
        var field = com.example.monitoring.dto.ManualBlockRequestDto.class.getDeclaredField("reason");
        var annotation = field.getAnnotation(jakarta.validation.constraints.NotBlank.class);
        org.assertj.core.api.Assertions.assertThat(annotation)
                .as("reason 필드에 @NotBlank 어노테이션이 있어야 함")
                .isNotNull();
    }

    // ----------------------------------------------------------------
    // POST /unblock
    // ----------------------------------------------------------------

    @Test
    @DisplayName("POST /{id}/unblock - 차단 해제 성공 → 200")
    void unblock_success_returns200() throws Exception {
        BlockStatusResponseDto dto = BlockStatusResponseDto.builder()
                .databaseConfigId(2L)
                .databaseName("blocked-db")
                .status(TargetDbStatus.UNKNOWN)
                .enabled(true)
                .build();

        when(projectIsolationService.unblock(eq(2L), anyString())).thenReturn(dto);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("reason", "점검 완료", "approvedBy", "ops-team"));

        mockMvc.perform(post(BASE_URL + "/2/unblock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("POST /{id}/unblock - 차단 상태 아닌 DB 해제 시 → 409 Conflict")
    void unblock_notBlocked_returns409() throws Exception {
        when(projectIsolationService.unblock(eq(1L), anyString()))
                .thenThrow(new IllegalStateException("DB [id=1] is not currently BLOCKED."));

        String requestBody = objectMapper.writeValueAsString(
                Map.of("reason", "해제", "approvedBy", "admin"));

        mockMvc.perform(post(BASE_URL + "/1/unblock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());
    }

    // ----------------------------------------------------------------
    // POST /toggle
    // ----------------------------------------------------------------

    @Test
    @DisplayName("POST /{id}/toggle - 토글 성공 → 200")
    void toggle_success_returns200() throws Exception {
        BlockStatusResponseDto dto = BlockStatusResponseDto.builder()
                .databaseConfigId(1L)
                .databaseName("test-db")
                .status(TargetDbStatus.BLOCKED)
                .enabled(false)
                .build();

        when(projectIsolationService.toggle(eq(1L), anyString(), anyString())).thenReturn(dto);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("reason", "토글 테스트", "approvedBy", "admin"));

        mockMvc.perform(post(BASE_URL + "/1/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());
    }
}
