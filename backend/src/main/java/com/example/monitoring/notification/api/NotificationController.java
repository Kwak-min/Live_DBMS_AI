package com.example.monitoring.notification.api;

import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.common.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class NotificationController {

    private static final String UTC_MILLIS_PATTERN =
            "^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])"
                    + "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\\.[0-9]{3}Z$";

    private final NotificationRecipientService service;

    public NotificationController(NotificationRecipientService service) {
        this.service = service;
    }

    @GetMapping("/push-config")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Get Web Push public configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "VAPID public key"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "503", description = "Web Push configuration unavailable")
    })
    public ResponseEntity<PushConfigResponse> pushConfig() {
        return ok(service.pushConfig());
    }

    @GetMapping("/push-subscriptions")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "List the current user's active Push subscriptions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Owned active Push subscriptions"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required")
    })
    public ResponseEntity<List<PushSubscriptionResponse>> listPush(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ok(service.listPush(principal));
    }

    @PostMapping("/push-subscriptions")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Register or refresh a session-bound Push subscription")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Owned subscription refreshed"),
            @ApiResponse(responseCode = "201", description = "Push subscription created"),
            @ApiResponse(responseCode = "400", description = "Invalid Push subscription"),
            @ApiResponse(responseCode = "401", description = "Access token or session invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "409", description = "Recipient limit or endpoint ownership conflict")
    })
    public ResponseEntity<PushSubscriptionResponse> registerPush(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PushSubscriptionRequest request
    ) {
        PushRegistration registration = service.registerPush(principal, request);
        if (!registration.created()) {
            return ok(registration.subscription());
        }
        return ResponseEntity.created(URI.create(
                        "/api/v1/notifications/push-subscriptions/" + registration.subscription().id()))
                .cacheControl(CacheControl.noStore())
                .body(registration.subscription());
    }

    @DeleteMapping("/push-subscriptions/{id}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(summary = "Delete an owned Push subscription")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Push subscription deleted; no body"),
            @ApiResponse(responseCode = "400", description = "Invalid subscription id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "USER or ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Owned Push subscription not found")
    })
    public ResponseEntity<Void> deletePush(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Parameter(description = "Positive JavaScript-safe Push subscription id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @PathVariable String id
    ) {
        service.deletePush(principal, id);
        return noContent();
    }

    @GetMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List Slack notification webhooks")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook page"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")
    })
    public ResponseEntity<PageResponse<WebhookResponse>> listWebhooks(
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0",
                    maximum = "10000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1",
                    maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size
    ) {
        return ok(service.listWebhooks(page, size));
    }

    @PostMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a Slack notification webhook")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Webhook created"),
            @ApiResponse(responseCode = "400", description = "Invalid webhook"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "409", description = "Webhook limit reached")
    })
    public ResponseEntity<WebhookResponse> createWebhook(@Valid @RequestBody WebhookInput input) {
        WebhookResponse response = service.createWebhook(input);
        return ResponseEntity.created(URI.create("/api/v1/notifications/webhooks/" + response.id()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping("/webhooks/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a Slack notification webhook")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook updated"),
            @ApiResponse(responseCode = "400", description = "Invalid webhook id or patch"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Webhook not found")
    })
    public ResponseEntity<WebhookResponse> patchWebhook(
            @Parameter(description = "Positive JavaScript-safe webhook id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @PathVariable String id,
            @Valid @RequestBody WebhookPatch patch
    ) {
        return ok(service.patchWebhook(id, patch));
    }

    @DeleteMapping("/webhooks/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a Slack notification webhook")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Webhook absent or deleted; no body"),
            @ApiResponse(responseCode = "400", description = "Invalid webhook id"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")
    })
    public ResponseEntity<Void> deleteWebhook(
            @Parameter(description = "Positive JavaScript-safe webhook id",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1",
                            maximum = "9007199254740991"))
            @PathVariable String id
    ) {
        service.deleteWebhook(id);
        return noContent();
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Search notification delivery history")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification delivery page"),
            @ApiResponse(responseCode = "400", description = "Invalid filter, range, or pagination"),
            @ApiResponse(responseCode = "401", description = "Access token invalid"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required")
    })
    public ResponseEntity<PageResponse<DeliveryResponse>> listDeliveries(
            @Parameter(description = "Canonical lowercase incident UUID",
                    schema = @Schema(type = "string", format = "uuid",
                            pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
            @RequestParam(required = false) String incidentId,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"WEB_PUSH", "SLACK"}))
            @RequestParam(required = false) String channel,
            @Parameter(schema = @Schema(type = "string",
                    allowableValues = {"PENDING", "SENT", "FAILED", "CANCELLED"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "Inclusive UTC-millisecond createdAt lower bound; provide with end",
                    schema = @Schema(type = "string", format = "date-time", pattern = UTC_MILLIS_PATTERN,
                            example = "2026-09-27T12:00:00.000Z"))
            @RequestParam(required = false) String start,
            @Parameter(description = "Exclusive UTC-millisecond createdAt upper bound; provide with start",
                    schema = @Schema(type = "string", format = "date-time", pattern = UTC_MILLIS_PATTERN,
                            example = "2026-09-28T12:00:00.000Z"))
            @RequestParam(required = false) String end,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0",
                    maximum = "10000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1",
                    maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size
    ) {
        return ok(service.listDeliveries(
                incidentId, channel, status, start, end, page, size));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
