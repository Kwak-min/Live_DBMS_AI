package com.example.monitoring.database.web;

import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.database.dto.DatabaseCreateRequest;
import com.example.monitoring.database.dto.DatabaseResponse;
import com.example.monitoring.database.dto.DatabaseUpdateRequest;
import com.example.monitoring.database.service.DatabaseConfigService;
import com.example.monitoring.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/databases")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class DatabaseConfigController {

    private final DatabaseConfigService databaseConfigService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register database", description = "ADMIN only. Stores encrypted credentials, requires an allowed host and port, and enforces a 20 active target limit. Registration does not test connectivity.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Database configuration created with Location header"),
            @ApiResponse(responseCode = "400", description = "Invalid input or disallowed target address"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "409", description = "Active database limit reached")})
    public ResponseEntity<DatabaseResponse> create(@Valid @RequestBody DatabaseCreateRequest request) {
        DatabaseResponse response = databaseConfigService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/databases/" + response.id()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping
    @Operation(summary = "List databases", description = "USER or ADMIN. Nondeleted targets ordered by id ascending; page 0..10000, size 1..100.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Database page"),
            @ApiResponse(responseCode = "400", description = "Invalid filter or pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid")})
    public ResponseEntity<PageResponse<DatabaseResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean enabled) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(databaseConfigService.list(page, size, enabled));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get database", description = "USER or ADMIN. Credentials and ciphertext are never returned.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Database configuration"),
            @ApiResponse(responseCode = "400", description = "Invalid id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "404", description = "Database not found or deleted")})
    public ResponseEntity<DatabaseResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(databaseConfigService.get(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update database", description = "ADMIN only. Requires current configVersion and at least one changed field; increments configVersion and resets observed status.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated database configuration"),
            @ApiResponse(responseCode = "400", description = "Invalid patch or target address"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Database not found or deleted"),
            @ApiResponse(responseCode = "409", description = "configVersion mismatch")})
    public ResponseEntity<DatabaseResponse> update(@PathVariable Long id,
                                                    @RequestBody DatabaseUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(databaseConfigService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete database", description = "ADMIN only. Soft deletes the target. Missing or already deleted ids also return 204.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Deleted or already absent; no body"),
            @ApiResponse(responseCode = "400", description = "Invalid id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")})
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        databaseConfigService.delete(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
