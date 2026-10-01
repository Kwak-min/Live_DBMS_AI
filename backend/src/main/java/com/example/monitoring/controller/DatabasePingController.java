package com.example.monitoring.controller;

import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.service.DatabaseHealthService;
import com.example.monitoring.common.api.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/databases")
@RequiredArgsConstructor
@Tag(name = "Database Health API", description = "One-off connectivity and version diagnostics for a target MariaDB (ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class DatabasePingController {

    private final DatabaseHealthService databaseHealthService;

    @PostMapping("/{id}/ping")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ping target database", description = "ADMIN only. Executes SELECT 1 and SELECT VERSION() once; a target connection failure returns 200 with DOWN. Does not update scheduled collection state. Limited to once per target every 10 seconds.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "UP or DOWN diagnostic result"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (id is not a positive Id)"),
            @ApiResponse(responseCode = "401",
                    description = "AUTH_REQUIRED, ACCESS_TOKEN_EXPIRED, INVALID_TOKEN or SESSION_REVOKED"),
            @ApiResponse(responseCode = "403", description = "FORBIDDEN: ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND (missing or deleted)"),
            @ApiResponse(responseCode = "429",
                    description = "RATE_LIMITED: once per target every 10 seconds, or the per-user API limit",
                    headers = @Header(name = "Retry-After", description = "Seconds to wait")),
            @ApiResponse(responseCode = "503",
                    description = "DEPENDENCY_UNAVAILABLE: system database or rate-limit store unavailable")})
    public ResponseEntity<DbPingResponseDto> pingDatabase(@PathVariable("id") Long id) {
        return databaseHealthService.pingDatabase(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND",
                        "DB 설정을 찾을 수 없습니다."));
    }
}
