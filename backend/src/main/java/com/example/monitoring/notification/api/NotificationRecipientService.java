package com.example.monitoring.notification.api;

import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.config.VapidUnavailableException;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository.PageSlice;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository.PushRow;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository.WebhookRow;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository.WebhookSecretRow;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.partc.api.PartCPage;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.partc.api.PartCQueryWindow;
import com.example.monitoring.service.AuditEventService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationRecipientService {

    private static final int RECIPIENT_LIMIT = 10;

    private final NotificationRecipientRepository repository;
    private final AuthService authService;
    private final PushEndpointPolicy pushEndpointPolicy;
    private final SlackWebhookPolicy slackWebhookPolicy;
    private final NotificationSecretCodec secrets;
    private final VapidConfigurationProvider vapidConfigurationProvider;
    private final PartCQueryValidator queryValidator;
    private final AuditEventService audits;
    private final Clock clock;

    public NotificationRecipientService(
            NotificationRecipientRepository repository,
            AuthService authService,
            PushEndpointPolicy pushEndpointPolicy,
            SlackWebhookPolicy slackWebhookPolicy,
            NotificationSecretCodec secrets,
            VapidConfigurationProvider vapidConfigurationProvider,
            PartCQueryValidator queryValidator,
            AuditEventService audits,
            Clock clock
    ) {
        this.repository = repository;
        this.authService = authService;
        this.pushEndpointPolicy = pushEndpointPolicy;
        this.slackWebhookPolicy = slackWebhookPolicy;
        this.secrets = secrets;
        this.vapidConfigurationProvider = vapidConfigurationProvider;
        this.queryValidator = queryValidator;
        this.audits = audits;
        this.clock = clock;
    }

    public PushConfigResponse pushConfig() {
        try {
            return new PushConfigResponse(vapidConfigurationProvider.requireConfigured().publicKey());
        } catch (VapidUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "Web Push configuration is unavailable.");
        }
    }

    @Transactional(readOnly = true)
    public List<PushSubscriptionResponse> listPush(AuthPrincipal principal) {
        requirePrincipal(principal);
        return repository.listActivePush(principal.userId()).stream()
                .map(NotificationRecipientService::pushResponse)
                .toList();
    }

    @Transactional
    public PushRegistration registerPush(AuthPrincipal principal, PushSubscriptionRequest request) {
        requirePrincipal(principal);
        Objects.requireNonNull(request, "request");
        validatedPushEndpoint(request.endpoint());
        String endpoint = request.endpoint();
        long nowEpochMillis = now().toEpochMilli();
        validateExpiration(request.expirationTime(), nowEpochMillis);
        PushKeys keys = Objects.requireNonNull(request.keys(), "keys");
        byte[] endpointHash = validatedEndpointHash(endpoint);

        // The shared auth lock must remain the first database operation in this transaction.
        if (!authService.lockSessionUsable(principal.sessionId(), principal.userId())) {
            throw sessionRevoked();
        }
        repository.lockEndpoint(endpointHash);
        PushRow existing = repository.findActivePushByHashForUpdate(endpointHash).orElse(null);
        Instant now = now();
        PushRow stored;
        boolean created;
        if (existing != null) {
            if (existing.userId() != principal.userId()) {
                throw recipientLimit("The endpoint is already registered.");
            }
            EncryptedValue encrypted = validatedPushEncryption(
                    existing.id(), endpoint, keys.p256dh(), keys.auth());
            stored = repository.updatePush(existing, principal.sessionId(), encrypted,
                    request.expirationTime(), now);
            created = false;
        } else {
            if (repository.countActivePush(principal.userId()) >= RECIPIENT_LIMIT) {
                throw recipientLimit("A user may register at most 10 active Push subscriptions.");
            }
            long id = ApiId.require(repository.nextPushId(), "id");
            EncryptedValue encrypted = validatedPushEncryption(
                    id, endpoint, keys.p256dh(), keys.auth());
            stored = repository.insertPush(id, principal.userId(), principal.sessionId(), endpointHash,
                    encrypted, request.expirationTime(), now);
            created = true;
        }
        audits.successCurrent(AuditAction.PUSH_REGISTERED, AuditTargetType.PUSH_SUBSCRIPTION,
                Long.toString(stored.id()), null,
                created ? "Push subscription registered" : "Push subscription refreshed");
        return new PushRegistration(created, pushResponse(stored));
    }

    @Transactional
    public void deletePush(AuthPrincipal principal, String rawId) {
        requirePrincipal(principal);
        long id = queryValidator.requiredId(rawId, "id");
        repository.lockPendingPushDeliveries(id);
        PushRow owned = repository.findOwnedPushForUpdate(id, principal.userId())
                .orElseThrow(NotificationRecipientService::pushNotFound);
        Instant now = now();
        repository.tombstonePush(owned.id(), now);
        audits.successCurrent(AuditAction.PUSH_DELETED, AuditTargetType.PUSH_SUBSCRIPTION,
                Long.toString(owned.id()), null, "Push subscription deleted");
    }

    @Transactional(readOnly = true)
    public PageResponse<WebhookResponse> listWebhooks(String rawPage, String rawSize) {
        PartCPage page = queryValidator.page(rawPage, rawSize);
        PageSlice<WebhookRow> slice = repository.listWebhooks(page);
        return page(slice.items().stream().map(NotificationRecipientService::webhookResponse).toList(),
                page, slice.total());
    }

    @Transactional
    public WebhookResponse createWebhook(WebhookInput input) {
        Objects.requireNonNull(input, "input");
        String name = validName(input.name());
        requireSlackProvider(input.provider());
        validatedSlackUrl(input.url());
        String url = input.url();
        boolean enabled = Boolean.TRUE.equals(input.enabled());

        repository.lockWebhookNamespace();
        if (repository.countNonDeletedWebhooks() >= RECIPIENT_LIMIT) {
            throw recipientLimit("At most 10 webhooks may be registered.");
        }
        long id = ApiId.require(repository.nextWebhookId(), "id");
        EncryptedValue encrypted = validatedSlackEncryption(id, url);
        WebhookRow stored = repository.insertWebhook(id, name, encrypted, enabled, now());
        audits.successCurrent(AuditAction.WEBHOOK_CREATED, AuditTargetType.WEBHOOK,
                Long.toString(stored.id()), null, "Notification webhook created");
        return webhookResponse(stored);
    }

    @Transactional
    public WebhookResponse patchWebhook(String rawId, WebhookPatch patch) {
        long id = queryValidator.requiredId(rawId, "id");
        Objects.requireNonNull(patch, "patch");
        if (patch.name() == null && patch.url() == null && patch.enabled() == null) {
            throw invalid("body", "REQUIRED", "At least one webhook field is required.");
        }
        String requestedName = patch.name() == null ? null : validName(patch.name());
        String requestedUrl = patch.url();
        if (requestedUrl != null) {
            validatedSlackUrl(requestedUrl);
        }

        if (Boolean.FALSE.equals(patch.enabled())) {
            repository.lockPendingWebhookDeliveries(id);
        }

        WebhookSecretRow existing = repository.findWebhookForUpdate(id)
                .orElseThrow(NotificationRecipientService::webhookNotFound);
        String name = requestedName == null ? existing.name() : requestedName;
        boolean enabled = patch.enabled() == null ? existing.enabled() : patch.enabled();
        EncryptedValue encrypted = requestedUrl == null ? null : validatedSlackEncryption(id, requestedUrl);
        WebhookRow stored = repository.updateWebhook(existing, name, encrypted, enabled, now());
        audits.successCurrent(AuditAction.WEBHOOK_UPDATED, AuditTargetType.WEBHOOK,
                Long.toString(stored.id()), null, "Notification webhook updated");
        return webhookResponse(stored);
    }

    @Transactional
    public void deleteWebhook(String rawId) {
        long id = queryValidator.requiredId(rawId, "id");
        repository.lockPendingWebhookDeliveries(id);
        if (repository.tombstoneWebhook(id, now())) {
            audits.successCurrent(AuditAction.WEBHOOK_DELETED, AuditTargetType.WEBHOOK,
                    Long.toString(id), null, "Notification webhook deleted");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> listDeliveries(
            String rawIncidentId,
            String rawChannel,
            String rawStatus,
            String rawStart,
            String rawEnd,
            String rawPage,
            String rawSize
    ) {
        UUID incidentId = queryValidator.optionalUuid(rawIncidentId, "incidentId");
        NotificationChannel channel = queryValidator.optionalEnum(
                rawChannel, NotificationChannel.class, "channel");
        DeliveryStatus status = queryValidator.optionalEnum(rawStatus, DeliveryStatus.class, "status");
        PartCQueryWindow window = queryValidator.window(rawStart, rawEnd);
        PartCPage page = queryValidator.page(rawPage, rawSize);
        PageSlice<DeliveryResponse> slice = repository.listDeliveries(
                incidentId, channel, status, window, page);
        return page(slice.items(), page, slice.total());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private URI validatedPushEndpoint(String endpoint) {
        if (endpoint == null || endpoint.codePointCount(0, endpoint.length()) > 2_048) {
            throw invalid("endpoint", "INVALID_VALUE", "The Push endpoint is invalid.");
        }
        try {
            return pushEndpointPolicy.validate(endpoint);
        } catch (IllegalArgumentException exception) {
            throw invalid("endpoint", "INVALID_VALUE", "The Push endpoint is invalid.");
        }
    }

    private byte[] validatedEndpointHash(String endpoint) {
        try {
            return secrets.endpointHash(endpoint);
        } catch (IllegalArgumentException exception) {
            throw invalid("endpoint", "INVALID_VALUE", "The Push endpoint is invalid.");
        }
    }

    private EncryptedValue validatedPushEncryption(long id, String endpoint, String p256dh, String auth) {
        byte[] publicKey = decodePushKey(p256dh, "keys.p256dh");
        if (publicKey.length != 65 || publicKey[0] != 0x04) {
            throw invalid("keys.p256dh", "INVALID_VALUE", "The Push public key is invalid.");
        }
        byte[] authSecret = decodePushKey(auth, "keys.auth");
        if (authSecret.length != 16) {
            throw invalid("keys.auth", "INVALID_VALUE", "The Push auth secret is invalid.");
        }
        try {
            return secrets.encryptPush(id, endpoint, p256dh, auth);
        } catch (IllegalArgumentException exception) {
            throw invalid("keys.p256dh", "INVALID_VALUE", "The Push public key is invalid.");
        }
    }

    private byte[] decodePushKey(String value, String field) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_-]+")) {
            throw invalid(field, "INVALID_VALUE", "The Push key is invalid.");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field, "INVALID_VALUE", "The Push key is invalid.");
        }
    }

    private URI validatedSlackUrl(String url) {
        if (url == null || url.codePointCount(0, url.length()) > 2_048) {
            throw invalid("url", "INVALID_VALUE", "The Slack webhook URL is invalid.");
        }
        try {
            return slackWebhookPolicy.validate(url);
        } catch (IllegalArgumentException exception) {
            throw invalid("url", "INVALID_VALUE", "The Slack webhook URL is invalid.");
        }
    }

    private EncryptedValue validatedSlackEncryption(long id, String url) {
        try {
            return secrets.encryptSlack(id, url);
        } catch (IllegalArgumentException exception) {
            throw invalid("url", "INVALID_VALUE", "The Slack webhook URL is invalid.");
        }
    }

    private void validateExpiration(Long expirationTime, long nowEpochMillis) {
        ApiId.validateOptional(expirationTime, "expirationTime");
        if (expirationTime != null && expirationTime <= nowEpochMillis) {
            throw invalid("expirationTime", "INVALID_VALUE", "expirationTime must be in the future.");
        }
    }

    private String validName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
            throw invalid("name", "INVALID_VALUE", "Webhook name must contain 1 to 100 characters.");
        }
        return name;
    }

    private void requireSlackProvider(String provider) {
        if (!"SLACK".equals(provider)) {
            throw invalid("provider", "INVALID_VALUE", "provider must be SLACK.");
        }
    }

    private void requirePrincipal(AuthPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.sessionId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication is required.");
        }
        ApiId.require(principal.userId(), "userId");
    }

    private static PushSubscriptionResponse pushResponse(PushRow row) {
        return new PushSubscriptionResponse(
                row.id(), row.createdAt(), row.updatedAt(), row.expirationTime());
    }

    private static WebhookResponse webhookResponse(WebhookRow row) {
        return new WebhookResponse(
                row.id(), row.name(), row.provider(), row.enabled(), row.createdAt(), row.updatedAt());
    }

    private static <T> PageResponse<T> page(List<T> items, PartCPage page, long total) {
        int totalPages = total == 0 ? 0 : (int) ((total + page.size() - 1) / page.size());
        return new PageResponse<>(items, page.page(), page.size(), total, totalPages);
    }

    private static ApiException sessionRevoked() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED",
                "Authentication session is no longer active.");
    }

    private static ApiException pushNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND",
                "Push subscription was not found.");
    }

    private static ApiException webhookNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND",
                "Notification webhook was not found.");
    }

    private static ApiException recipientLimit(String message) {
        return new ApiException(HttpStatus.CONFLICT, "RESOURCE_LIMIT_EXCEEDED", message);
    }

    private static ApiException invalid(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Please check the request values.",
                List.of(new FieldErrorResponse(field, code, message)));
    }
}
