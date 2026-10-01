package com.example.monitoring.controller;

import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.service.MetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Metric API", description = "Raw metric snapshots of target MariaDB databases (USER/ADMIN)")
public class MetricController {

    private static final String UNAUTHORIZED = "AUTH_REQUIRED, ACCESS_TOKEN_EXPIRED, INVALID_TOKEN or SESSION_REVOKED";
    private static final String RATE_LIMITED = "RATE_LIMITED: per-user API limit (30/s, burst 60)";
    private static final String UNAVAILABLE = "DEPENDENCY_UNAVAILABLE: system database or rate-limit store unavailable";
    private static final String UTC_TIME = "UTC Time, YYYY-MM-DDTHH:mm:ss.SSSZ";

    private final MetricService metricService;

    @GetMapping("/{dbId}/latest")
    @Operation(summary = "Get latest metric",
            description = "Latest snapshot collected with the target's current configVersion. 204 when none yet.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Latest Metric"),
            @ApiResponse(responseCode = "204", description = "No snapshot for the current configVersion yet; no body"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (dbId is not a positive Id)"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND (missing or deleted)"),
            @ApiResponse(responseCode = "429", description = RATE_LIMITED,
                    headers = @Header(name = "Retry-After", description = "Seconds to wait")),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE)})
    public ResponseEntity<MetricResponseDto> getLatestMetric(
            @Parameter(description = "Target database Id") @PathVariable Long dbId) {
        return metricService.getLatestMetric(dbId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{dbId}/recent")
    @Operation(summary = "Get recent metrics",
            description = "Most recent snapshots ordered by timestamp DESC, id DESC. Deleted targets' retained data "
                    + "is included.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Metric[]; [] when no data"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (invalid dbId or limit outside 1~1000)"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND (target record does not exist)"),
            @ApiResponse(responseCode = "429", description = RATE_LIMITED,
                    headers = @Header(name = "Retry-After", description = "Seconds to wait")),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE)})
    public ResponseEntity<List<MetricResponseDto>> getRecentMetrics(
            @Parameter(description = "Target database Id") @PathVariable Long dbId,
            @Parameter(description = "Number of snapshots",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "1000",
                            defaultValue = "50"))
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(metricService.getRecentMetrics(dbId, limit));
    }

    @GetMapping("/{dbId}/history")
    @Operation(summary = "Get metric history",
            description = "Snapshots with start <= timestamp < end ordered by timestamp ASC, id ASC. "
                    + "Range at most 24 hours; more than 20,000 results is rejected, never truncated.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Metric[]; [] when no data in the range"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (start/end missing or not UTC Time), "
                    + "INVALID_TIME_RANGE (start >= end or longer than 24 hours) or "
                    + "RESULT_LIMIT_EXCEEDED (more than 20,000 snapshots; narrow the range)"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND (target record does not exist)"),
            @ApiResponse(responseCode = "429", description = RATE_LIMITED,
                    headers = @Header(name = "Retry-After", description = "Seconds to wait")),
            @ApiResponse(responseCode = "503", description = UNAVAILABLE)})
    public ResponseEntity<List<MetricResponseDto>> getMetricHistory(
            @Parameter(description = "Target database Id") @PathVariable Long dbId,
            // 필수 여부는 서비스가 VALIDATION_ERROR(REQUIRED)로 검사하므로 바인딩은 선택으로 둔다.
            @Parameter(required = true, description = "Inclusive start, " + UTC_TIME,
                    schema = @Schema(type = "string", format = "date-time", example = "2026-09-28T03:00:00.000Z"))
            @RequestParam(required = false) String start,
            @Parameter(required = true, description = "Exclusive end, " + UTC_TIME,
                    schema = @Schema(type = "string", format = "date-time", example = "2026-09-28T04:00:00.000Z"))
            @RequestParam(required = false) String end) {
        return ResponseEntity.ok(metricService.getMetricHistory(dbId, start, end));
    }
}
