package com.example.monitoring.controller;

import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.service.DatabaseHealthService;
import com.example.monitoring.common.api.ApiException;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "Database Health API", description = "Endpoints for testing remote MariaDB connectivity and version check")
@SecurityRequirement(name = "bearerAuth")
public class DatabasePingController {

    private final DatabaseHealthService databaseHealthService;

    @PostMapping("/{id}/ping")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ping target database", description = "ADMIN only. Executes SELECT 1 and SELECT VERSION() once; a target connection failure returns 200 with DOWN. Does not update scheduled collection state. Limited to once per target every 10 seconds.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "UP or DOWN diagnostic result"),
            @ApiResponse(responseCode = "400", description = "Invalid id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Database not found or deleted"),
            @ApiResponse(responseCode = "429", description = "Per-target Ping rate limit"),
            @ApiResponse(responseCode = "503", description = "Dependency unavailable")})
    public ResponseEntity<DbPingResponseDto> pingDatabase(@PathVariable("id") Long id) {
        return databaseHealthService.pingDatabase(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND",
                        "DB 설정을 찾을 수 없습니다."));
    }
}
