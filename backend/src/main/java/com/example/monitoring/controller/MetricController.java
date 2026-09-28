package com.example.monitoring.controller;

import com.example.monitoring.dto.MetricResponseDto;
import com.example.monitoring.service.MetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
@Tag(name = "Metric API", description = "Endpoints for fetching target MariaDB performance metrics")
public class MetricController {

    private final MetricService metricService;

    @GetMapping("/{dbId}/latest")
    @Operation(summary = "Get Latest Metric", description = "Fetches the most recent metric collection snapshot for a target database")
    public ResponseEntity<MetricResponseDto> getLatestMetric(@PathVariable Long dbId) {
        return metricService.getLatestMetric(dbId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{dbId}/recent")
    @Operation(summary = "Get Recent Metrics", description = "Fetches recent N metric snapshots for a target database")
    public ResponseEntity<List<MetricResponseDto>> getRecentMetrics(
            @PathVariable Long dbId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(metricService.getRecentMetrics(dbId, limit));
    }

    @GetMapping("/{dbId}/history")
    @Operation(summary = "Get Metric History", description = "Fetches metric snapshot history within a specified time range")
    public ResponseEntity<List<MetricResponseDto>> getMetricHistory(
            @PathVariable Long dbId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(metricService.getMetricHistory(dbId, start, end));
    }
}
