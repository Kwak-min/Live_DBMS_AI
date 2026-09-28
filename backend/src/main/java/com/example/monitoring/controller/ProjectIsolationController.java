package com.example.monitoring.controller;

import com.example.monitoring.dto.BlockStatusResponseDto;
import com.example.monitoring.dto.ManualBlockRequestDto;
import com.example.monitoring.service.ProjectIsolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 프로젝트 격리 및 차단 제어 REST API.
 *
 * <pre>
 * GET  /api/v1/projects/{id}/block-status  — 특정 DB 차단 상태 조회
 * GET  /api/v1/projects/blocked            — 차단된 전체 DB 목록
 * POST /api/v1/projects/{id}/block         — 수동 차단
 * POST /api/v1/projects/{id}/unblock       — 수동 해제 + 승인
 * POST /api/v1/projects/{id}/toggle        — 차단 상태 토글
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Project Isolation API", description = "DB 프로젝트 격리 및 차단(Block) 제어 엔드포인트")
public class ProjectIsolationController {

    private final ProjectIsolationService projectIsolationService;

    // ----------------------------------------------------------------
    // 조회
    // ----------------------------------------------------------------

    @GetMapping("/{id}/block-status")
    @Operation(
            summary = "차단 상태 조회",
            description = "특정 DB 설정의 현재 차단 상태와 활성 차단 상세 정보를 반환합니다."
    )
    public ResponseEntity<?> getBlockStatus(
            @Parameter(description = "DatabaseConfig ID") @PathVariable Long id) {
        try {
            return ResponseEntity.ok(projectIsolationService.getBlockStatus(id));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/blocked")
    @Operation(
            summary = "차단된 DB 전체 목록",
            description = "현재 BLOCKED 상태인 모든 DB 프로젝트 목록을 반환합니다."
    )
    public ResponseEntity<List<BlockStatusResponseDto>> getAllBlocked() {
        return ResponseEntity.ok(projectIsolationService.getAllBlockedProjects());
    }

    // ----------------------------------------------------------------
    // 수동 차단
    // ----------------------------------------------------------------

    @PostMapping("/{id}/block")
    @Operation(
            summary = "수동 차단",
            description = "관리자가 특정 DB를 수동으로 차단합니다. 이미 차단된 경우 409 반환."
    )
    public ResponseEntity<?> block(
            @Parameter(description = "DatabaseConfig ID") @PathVariable Long id,
            @Valid @RequestBody ManualBlockRequestDto request) {
        try {
            BlockStatusResponseDto result =
                    projectIsolationService.manualBlock(id, request.getReason(), request.getApprovedBy());
            return ResponseEntity.ok(result);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ----------------------------------------------------------------
    // 수동 해제 + 승인
    // ----------------------------------------------------------------

    @PostMapping("/{id}/unblock")
    @Operation(
            summary = "수동 해제 및 승인",
            description = "차단된 DB를 해제하고 수집을 재개합니다. 차단 상태가 아닌 경우 409 반환."
    )
    public ResponseEntity<?> unblock(
            @Parameter(description = "DatabaseConfig ID") @PathVariable Long id,
            @Valid @RequestBody ManualBlockRequestDto request) {
        try {
            BlockStatusResponseDto result =
                    projectIsolationService.unblock(id, request.getApprovedBy());
            return ResponseEntity.ok(result);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ----------------------------------------------------------------
    // 토글
    // ----------------------------------------------------------------

    @PostMapping("/{id}/toggle")
    @Operation(
            summary = "차단 상태 토글",
            description = "현재 BLOCKED이면 해제, 아니면 수동 차단합니다."
    )
    public ResponseEntity<?> toggle(
            @Parameter(description = "DatabaseConfig ID") @PathVariable Long id,
            @Valid @RequestBody ManualBlockRequestDto request) {
        try {
            BlockStatusResponseDto result =
                    projectIsolationService.toggle(id, request.getReason(), request.getApprovedBy());
            return ResponseEntity.ok(result);
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
