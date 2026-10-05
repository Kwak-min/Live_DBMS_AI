package com.example.monitoring.risk.policy;

import com.example.monitoring.common.config.OpenApiConfig;
import com.example.monitoring.risk.contract.RiskPolicy;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/databases/{id}/risk-policy")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RiskPolicyController {

    private final RiskPolicyService service;

    public RiskPolicyController(RiskPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get risk policy", description = "USER or ADMIN. Deleted targets return 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current risk policy"),
            @ApiResponse(responseCode = "400", description = "Invalid target id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Database target or policy not found")
    })
    public ResponseEntity<RiskPolicy> get(
            @Parameter(description = "Positive JavaScript-safe database target id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @PathVariable String id
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace risk policy",
            description = "ADMIN only. Replaces the complete two-rule policy using optimistic versioning.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated risk policy"),
            @ApiResponse(responseCode = "400", description = "Invalid target id or policy"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Database target or policy not found"),
            @ApiResponse(responseCode = "409", description = "Policy version mismatch")
    })
    public ResponseEntity<RiskPolicy> update(
            @Parameter(description = "Positive JavaScript-safe database target id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @PathVariable String id,
            @RequestBody PolicyWrite request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(id, request));
    }
}
