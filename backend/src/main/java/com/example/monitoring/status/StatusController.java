package com.example.monitoring.status;

import com.example.monitoring.common.config.OpenApiConfig;
import com.example.monitoring.risk.contract.StatusSnapshot;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/databases")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class StatusController {

    private final StatusQueryService service;

    public StatusController(StatusQueryService service) {
        this.service = service;
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Get current monitoring status",
            description = "USER or ADMIN. Returns the current status and every OPEN incident id for an undeleted target.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current monitoring status"),
            @ApiResponse(responseCode = "400", description = "Invalid target id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Database target or status not found")
    })
    public ResponseEntity<StatusSnapshot> get(
            @Parameter(description = "Positive JavaScript-safe database target id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @PathVariable String id
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
}
