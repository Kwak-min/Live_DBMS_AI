package com.example.monitoring.auth.web;

import com.example.monitoring.auth.dto.UpdateUserRoleRequest;
import com.example.monitoring.auth.dto.UpdateUserStatusRequest;
import com.example.monitoring.auth.dto.UserResponse;
import com.example.monitoring.auth.service.UserAccountService;
import com.example.monitoring.common.api.PageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UserManagementController {

    private final UserAccountService userAccountService;

    @GetMapping
    @Operation(summary = "List users", description = "ADMIN only. Ordered by id ascending; page 0..10000, size 1..100.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "User page"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")})
    public ResponseEntity<PageResponse<UserResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(userAccountService.listUsers(page, size));
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Change user role", description = "ADMIN only. Revokes all sessions when the role changes; the last active ADMIN is protected.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated user"),
            @ApiResponse(responseCode = "400", description = "Invalid role or id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "User not found"),
            @ApiResponse(responseCode = "409", description = "Last active ADMIN cannot be demoted")})
    public ResponseEntity<UserResponse> updateRole(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(userAccountService.updateRole(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change user status", description = "ADMIN only. Revokes all sessions when enabled changes; the last active ADMIN is protected.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated user"),
            @ApiResponse(responseCode = "400", description = "Invalid status or id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "User not found"),
            @ApiResponse(responseCode = "409", description = "Last active ADMIN cannot be disabled")})
    public ResponseEntity<UserResponse> updateStatus(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(userAccountService.updateStatus(id, request));
    }
}
