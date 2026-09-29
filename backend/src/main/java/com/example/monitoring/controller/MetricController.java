package com.example.monitoring.controller;

import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.service.MetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

    private final MetricService metricService;

    @GetMapping("/{dbId}/latest")
    @Operation(summary = "Get latest metric",
            description = "Latest snapshot collected with the target's current configVersion. 204 when none yet.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Latest Metric"),
            @ApiResponse(responseCode = "204", description = "No snapshot for the current configVersion yet"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND (missing or deleted)")})
    public ResponseEntity<MetricResponseDto> getLatestMetric(@PathVariable Long dbId) {
        return metricService.getLatestMetric(dbId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{dbId}/recent")
    @Operation(summary = "Get recent metrics",
            description = "Most recent snapshots ordered by timestamp DESC, id DESC. Deleted targets' retained data "
                    + "is included.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Metric[] (may be empty)"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (limit outside 1~1000)"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND")})
    public ResponseEntity<List<MetricResponseDto>> getRecentMetrics(
            @PathVariable Long dbId,
            @Parameter(description = "1~1000, default 50") @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(metricService.getRecentMetrics(dbId, limit));
    }

    @GetMapping("/{dbId}/history")
    @Operation(summary = "Get metric history",
            description = "Snapshots with start <= timestamp < end ordered by timestamp ASC, id ASC. "
                    + "Range at most 24 hours; more than 20,000 results is rejected, never truncated.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Metric[] (may be empty)"),
            @ApiResponse(responseCode = "400",
                    description = "VALIDATION_ERROR, INVALID_TIME_RANGE or RESULT_LIMIT_EXCEEDED"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND")})
    public ResponseEntity<List<MetricResponseDto>> getMetricHistory(
            @PathVariable Long dbId,
            @Parameter(description = "UTC, inclusive. e.g. 2026-09-28T03:00:00.000Z")
            @RequestParam(required = false) String start,
            @Parameter(description = "UTC, exclusive. e.g. 2026-09-28T04:00:00.000Z")
            @RequestParam(required = false) String end) {
        return ResponseEntity.ok(metricService.getMetricHistory(dbId, start, end));
    }
}
