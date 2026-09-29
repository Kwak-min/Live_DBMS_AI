package com.example.monitoring.audit.web;

import com.example.monitoring.audit.dto.*;
import com.example.monitoring.audit.service.AuditHistoryService;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditResult;
import com.example.monitoring.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AuditHistoryController {
    private final AuditHistoryService auditHistoryService;

    @GetMapping("/audit-logs")
    @Operation(summary = "Search audit events", description = "ADMIN only. Filters are optional; default range is the last 24 hours, maximum 30 days, with start inclusive and end exclusive. Ordered by occurredAt then id descending.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Audit event page"),
            @ApiResponse(responseCode = "400", description = "Invalid filter, range, or pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")})
    public ResponseEntity<PageResponse<AuditEventResponse>> audits(@RequestParam(required = false) Long actorId,
            @RequestParam(required = false) Long databaseConfigId, @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditResult result, @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auditHistoryService.audits(actorId, databaseConfigId, action, result, start, end, page, size));
    }

    @GetMapping("/access-logs")
    @Operation(summary = "Search access logs", description = "ADMIN only. Filters are optional; default range is the last 24 hours, maximum 30 days, with start inclusive and end exclusive. Ordered by occurredAt then id descending.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Access log page"),
            @ApiResponse(responseCode = "400", description = "Invalid filter, range, or pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")})
    public ResponseEntity<PageResponse<AccessLogResponse>> access(@RequestParam(required = false) Long actorId,
            @RequestParam(required = false) Instant start, @RequestParam(required = false) Instant end,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auditHistoryService.access(actorId, start, end, page, size));
    }
}
