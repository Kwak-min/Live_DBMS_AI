package com.example.monitoring.incident.query;

import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.common.config.OpenApiConfig;
import com.example.monitoring.risk.contract.Incident;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class IncidentQueryController {

    private static final String UTC_MILLIS_PATTERN =
            "^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])"
                    + "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\\.[0-9]{3}Z$";

    private final IncidentQueryService service;

    public IncidentQueryController(IncidentQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search incidents",
            description = "USER or ADMIN. Uses a default 24-hour half-open window, up to 30 days, ordered by openedAt descending then incidentId ascending.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Incident page"),
            @ApiResponse(responseCode = "400", description = "Invalid filter, range, or pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required")
    })
    public ResponseEntity<PageResponse<Incident>> list(
            @Parameter(description = "Positive JavaScript-safe database target id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @RequestParam(required = false) String databaseConfigId,
            @Parameter(description = "Inclusive UTC-millisecond openedAt lower bound; provide with end",
                    schema = @Schema(type = "string", format = "date-time", pattern = UTC_MILLIS_PATTERN,
                            example = "2026-09-27T12:00:00.000Z"))
            @RequestParam(required = false) String start,
            @Parameter(description = "Exclusive UTC-millisecond openedAt upper bound; provide with start",
                    schema = @Schema(type = "string", format = "date-time", pattern = UTC_MILLIS_PATTERN,
                            example = "2026-09-28T12:00:00.000Z"))
            @RequestParam(required = false) String end,
            @Parameter(schema = @Schema(type = "string",
                    allowableValues = {"WARNING", "CRITICAL", "FATAL"}))
            @RequestParam(required = false) String severity,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"OPEN", "RESOLVED"}))
            @RequestParam(required = false) String status,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0",
                    maximum = "10000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1",
                    maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(databaseConfigId, start, end, severity, status, page, size));
    }

    @GetMapping("/{incidentId}")
    @Operation(summary = "Get incident", description = "USER or ADMIN. Deleted-target history remains queryable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Incident snapshot"),
            @ApiResponse(responseCode = "400", description = "Invalid incident UUID"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Incident not found")
    })
    public ResponseEntity<Incident> get(
            @Parameter(description = "Canonical lowercase incident UUID",
                    schema = @Schema(type = "string", format = "uuid",
                            pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
            @PathVariable String incidentId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(incidentId));
    }
}
