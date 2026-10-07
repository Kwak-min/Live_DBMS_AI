package com.example.monitoring.ai.web;

import com.example.monitoring.ai.service.AiReportService;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.common.config.OpenApiConfig;
import com.example.monitoring.common.web.AuditRequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI Insights API", description = "AI-generated (Gemini or Claude) daily DB health reports and risky query analysis")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AiReportController {

    private final AiReportService service;
    private final AuditRequestContext auditRequestContext;

    public AiReportController(AiReportService service, AuditRequestContext auditRequestContext) {
        this.service = service;
        this.auditRequestContext = auditRequestContext;
    }

    @GetMapping("/ai/status")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Get AI availability",
            description = "USER or ADMIN. Lets the UI hide generation buttons when AI is off.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI availability"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required")
    })
    public ResponseEntity<AiStatusResponse> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
    }

    @PostMapping("/databases/{id}/ai/daily-report")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Generate daily report",
            description = "ADMIN only. Starts generating the AI health report for one local day (default: yesterday "
                    + "in the report time zone) and returns the PENDING report. Poll GET /api/v1/ai/reports/{reportId} "
                    + "until status is SUCCEEDED or FAILED (usually 10-60 seconds).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "PENDING report created"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: invalid id or date"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AI_REPORT_IN_PROGRESS for the same target and date"),
            @ApiResponse(responseCode = "422", description = "AI_NO_DATA: no metrics were collected that day"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED per target",
                    headers = @Header(name = "Retry-After", description = "Seconds to wait")),
            @ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE, AI_BUSY or DEPENDENCY_UNAVAILABLE")
    })
    public ResponseEntity<AiReportResponse> requestDailyReport(
            @PathVariable("id") Long id,
            @Parameter(description = "Local date YYYY-MM-DD in the report time zone; before today, within metric "
                    + "retention. Defaults to yesterday.", schema = @Schema(type = "string", format = "date"))
            @RequestParam(required = false) String date
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(service.requestDailyReport(id, date, auditRequestContext.current().actorId()));
    }

    @PostMapping("/databases/{id}/ai/query-analysis")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Analyze risky queries",
            description = "ADMIN only. Reads the heaviest normalized statements from the target's performance_schema "
                    + "(or the running processlist when it is off), removes literals, and asks AI to flag risky ones. "
                    + "Returns the PENDING report; poll GET /api/v1/ai/reports/{reportId}.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "PENDING report created"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: invalid id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "DATABASE_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AI_REPORT_IN_PROGRESS for the same target"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED per target",
                    headers = @Header(name = "Retry-After", description = "Seconds to wait")),
            @ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE, AI_BUSY or DEPENDENCY_UNAVAILABLE")
    })
    public ResponseEntity<AiReportResponse> requestQueryAnalysis(@PathVariable("id") Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(service.requestQueryAnalysis(id, auditRequestContext.current().actorId()));
    }

    @GetMapping("/ai/reports")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "List AI reports", description = "USER or ADMIN. Newest first (requestedAt desc, id desc).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report page"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: invalid filter or pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required")
    })
    public ResponseEntity<PageResponse<AiReportResponse>> list(
            @RequestParam(required = false) Long databaseConfigId,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"DAILY_REPORT", "QUERY_ANALYSIS"}))
            @RequestParam(required = false) String type,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"PENDING", "SUCCEEDED", "FAILED"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "reportDate filter YYYY-MM-DD", schema = @Schema(type = "string", format = "date"))
            @RequestParam(required = false) String date,
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "10000", defaultValue = "0"))
            @RequestParam(required = false) Integer page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(databaseConfigId, type, status, date, page, size));
    }

    @GetMapping("/ai/reports/{reportId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Get AI report", description = "USER or ADMIN. Poll this while status is PENDING.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI report"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: invalid reportId"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "AI_REPORT_NOT_FOUND")
    })
    public ResponseEntity<AiReportResponse> get(@PathVariable("reportId") Long reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(reportId));
    }
}
