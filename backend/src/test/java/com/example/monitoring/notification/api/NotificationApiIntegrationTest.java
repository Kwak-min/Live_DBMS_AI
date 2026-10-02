package com.example.monitoring.notification.api;

import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.notification.config.VapidConfigurationProvider;
import com.example.monitoring.notification.config.VapidUnavailableException;
import com.example.monitoring.notification.persistence.NotificationRecipientRepository;
import com.example.monitoring.notification.security.NotificationSecretCodec;
import com.example.monitoring.notification.security.PushEndpointPolicy;
import com.example.monitoring.notification.security.SlackWebhookPolicy;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.service.AuditEventService;
import com.example.monitoring.support.EmbeddedPostgresSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({EmbeddedPostgresSupport.Config.class, NotificationRecipientRepository.class,
        NotificationRecipientService.class, PartCQueryValidator.class,
        NotificationApiIntegrationTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationApiIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00.000Z");
    private static final String ENDPOINT = "https://fcm.googleapis.com/push/test-device";
    private static final String P256DH =
            "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
    private static final String AUTH = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String SLACK = "https://hooks.slack.com/services/T000/B000/test-value";
    private static final byte[] HASH = new byte[32];
    private static final EncryptedValue PUSH_CIPHERTEXT =
            new EncryptedValue(1, new byte[12], new byte[17]);
    private static final EncryptedValue SLACK_CIPHERTEXT =
            new EncryptedValue(1, new byte[12], new byte[17]);

    @Autowired private NotificationRecipientService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @MockBean private AuthService auth;
    @MockBean private PushEndpointPolicy pushPolicy;
    @MockBean private SlackWebhookPolicy slackPolicy;
    @MockBean private NotificationSecretCodec secrets;
    @MockBean private VapidConfigurationProvider vapidConfigurationProvider;
    @MockBean private AuditEventService audits;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM push_subscriptions");
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM used_refresh_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void sessionOwnerRegistersSafelyAndAdminManagesSlackWithoutSecretResponses() throws Exception {
        Identity owner = identity("owner@example.com", UserRole.USER);
        AuthPrincipal principal = owner.principal();
        given(auth.lockSessionUsable(owner.sid(), owner.userId())).willReturn(true);
        given(pushPolicy.validate(ENDPOINT)).willReturn(URI.create(ENDPOINT));
        given(secrets.endpointHash(ENDPOINT)).willReturn(HASH.clone());
        given(secrets.encryptPush(anyLong(), org.mockito.ArgumentMatchers.eq(ENDPOINT),
                org.mockito.ArgumentMatchers.eq(P256DH), org.mockito.ArgumentMatchers.eq(AUTH)))
                .willReturn(PUSH_CIPHERTEXT);

        PushRegistration created = service.registerPush(principal,
                new PushSubscriptionRequest(ENDPOINT, NOW.plusSeconds(3600).toEpochMilli(),
                        new PushKeys(P256DH, AUTH)));
        PushRegistration updated = service.registerPush(principal,
                new PushSubscriptionRequest(ENDPOINT, NOW.plusSeconds(7200).toEpochMilli(),
                        new PushKeys(P256DH, AUTH)));

        assertThat(created.created()).isTrue();
        assertThat(updated.created()).isFalse();
        assertThat(updated.subscription().id()).isEqualTo(created.subscription().id());
        assertThat(service.listPush(principal)).containsExactly(updated.subscription());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscriptions", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT octet_length(payload_ciphertext) FROM push_subscriptions",
                Integer.class)).isEqualTo(17);
        String pushJson = mapper.writeValueAsString(updated.subscription());
        assertThat(pushJson).doesNotContain("endpoint", "p256dh", "auth", "ciphertext", "nonce");
        verify(audits, org.mockito.Mockito.times(2)).successCurrent(
                org.mockito.ArgumentMatchers.eq(AuditAction.PUSH_REGISTERED),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());

        given(slackPolicy.validate(SLACK)).willReturn(URI.create(SLACK));
        given(secrets.encryptSlack(anyLong(), org.mockito.ArgumentMatchers.eq(SLACK)))
                .willReturn(SLACK_CIPHERTEXT);
        WebhookResponse webhook = service.createWebhook(new WebhookInput("  operations  ", "SLACK", SLACK, true));
        WebhookResponse patched = service.patchWebhook(Long.toString(webhook.id()),
                new WebhookPatch("alerts", null, false));

        assertThat(webhook.name()).isEqualTo("operations");
        assertThat(patched.name()).isEqualTo("alerts");
        assertThat(patched.enabled()).isFalse();
        assertThat(mapper.writeValueAsString(patched))
                .doesNotContain("hooks.slack.com", "test-value", "url", "ciphertext", "nonce");
        assertThat(service.listWebhooks("0", "20").items()).containsExactly(patched);
    }

    @Test
    void foreignEndpointEleventhRecipientInactiveSessionAndForeignDeleteFailClosed() {
        Identity owner = identity("owner@example.com", UserRole.USER);
        Identity other = identity("other@example.com", UserRole.USER);
        preparePushMocks(owner);
        PushRegistration existing = service.registerPush(owner.principal(), request(ENDPOINT));

        given(auth.lockSessionUsable(other.sid(), other.userId())).willReturn(true);
        assertThatThrownBy(() -> service.registerPush(other.principal(), request(ENDPOINT)))
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> {
                            assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(failure.getCode()).isEqualTo("RESOURCE_LIMIT_EXCEEDED");
                        });
        assertThatThrownBy(() -> service.deletePush(other.principal(), Long.toString(existing.subscription().id())))
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        for (int index = 1; index < 10; index++) {
            String endpoint = ENDPOINT + '-' + index;
            given(pushPolicy.validate(endpoint)).willReturn(URI.create(endpoint));
            byte[] hash = new byte[32];
            hash[31] = (byte) index;
            given(secrets.endpointHash(endpoint)).willReturn(hash);
            given(secrets.encryptPush(anyLong(), org.mockito.ArgumentMatchers.eq(endpoint),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(PUSH_CIPHERTEXT);
            service.registerPush(owner.principal(), request(endpoint));
        }
        String eleventh = ENDPOINT + "-limit";
        given(pushPolicy.validate(eleventh)).willReturn(URI.create(eleventh));
        byte[] eleventhHash = new byte[32];
        eleventhHash[31] = 99;
        given(secrets.endpointHash(eleventh)).willReturn(eleventhHash);
        assertThatThrownBy(() -> service.registerPush(owner.principal(), request(eleventh)))
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("RESOURCE_LIMIT_EXCEEDED"));

        given(auth.lockSessionUsable(owner.sid(), owner.userId())).willReturn(false);
        assertThatThrownBy(() -> service.registerPush(owner.principal(), request(ENDPOINT + "-inactive")))
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void omittedExpirationPropertyIsRejectedWhileExplicitNullIsAccepted() throws Exception {
        String base = """
                {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(ENDPOINT, P256DH, AUTH);

        assertThatThrownBy(() -> mapper.readValue(base, PushSubscriptionRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        PushSubscriptionRequest explicitNull = mapper.readValue(
                base.replace(",\"keys\"", ",\"expirationTime\":null,\"keys\""),
                PushSubscriptionRequest.class);
        assertThat(explicitNull.expirationTime()).isNull();
    }

    @Test
    void requestBodiesRejectJacksonScalarCoercion() {
        String stringExpiration = """
                {"endpoint":"%s","expirationTime":"123","keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(ENDPOINT, P256DH, AUTH);
        String numericName = """
                {"name":123,"provider":"SLACK","url":"%s","enabled":true}
                """.formatted(SLACK);
        String stringBoolean = """
                {"name":"operations","provider":"SLACK","url":"%s","enabled":"true"}
                """.formatted(SLACK);

        assertThatThrownBy(() -> mapper.readValue(stringExpiration, PushSubscriptionRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue(numericName, WebhookInput.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue(stringBoolean, WebhookInput.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void validatesPushKeyFieldsPreciselyAndMapsMissingVapidToServiceUnavailable() {
        Identity owner = identity("keys@example.com", UserRole.USER);
        given(auth.lockSessionUsable(owner.sid(), owner.userId())).willReturn(true);
        given(pushPolicy.validate(ENDPOINT)).willReturn(URI.create(ENDPOINT));
        given(secrets.endpointHash(ENDPOINT)).willReturn(HASH.clone());

        assertThatThrownBy(() -> service.registerPush(owner.principal(),
                new PushSubscriptionRequest(ENDPOINT, null, new PushKeys("AA", AUTH))))
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> assertThat(failure.getFieldErrors()).singleElement()
                                .extracting(com.example.monitoring.common.api.FieldErrorResponse::field)
                                .isEqualTo("keys.p256dh"));
        assertThatThrownBy(() -> service.registerPush(owner.principal(),
                new PushSubscriptionRequest(ENDPOINT, null, new PushKeys(P256DH, "AA"))))
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> assertThat(failure.getFieldErrors()).singleElement()
                                .extracting(com.example.monitoring.common.api.FieldErrorResponse::field)
                                .isEqualTo("keys.auth"));

        given(vapidConfigurationProvider.requireConfigured()).willThrow(new VapidUnavailableException());
        assertThatThrownBy(service::pushConfig)
                .isInstanceOfSatisfying(com.example.monitoring.common.api.ApiException.class,
                        failure -> {
                            assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(failure.getCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                        });
    }

    @Test
    void webhookNameLimitCountsUnicodeCodePoints() {
        String sixtyEmoji = "😀".repeat(60);
        given(slackPolicy.validate(SLACK)).willReturn(URI.create(SLACK));
        given(secrets.encryptSlack(anyLong(), org.mockito.ArgumentMatchers.eq(SLACK)))
                .willReturn(SLACK_CIPHERTEXT);

        WebhookResponse response = service.createWebhook(
                new WebhookInput(sixtyEmoji, "SLACK", SLACK, true));

        assertThat(response.name()).isEqualTo(sixtyEmoji);
    }

    private void preparePushMocks(Identity owner) {
        given(auth.lockSessionUsable(owner.sid(), owner.userId())).willReturn(true);
        given(pushPolicy.validate(ENDPOINT)).willReturn(URI.create(ENDPOINT));
        given(secrets.endpointHash(ENDPOINT)).willReturn(HASH.clone());
        given(secrets.encryptPush(anyLong(), org.mockito.ArgumentMatchers.eq(ENDPOINT),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(PUSH_CIPHERTEXT);
    }

    private PushSubscriptionRequest request(String endpoint) {
        return new PushSubscriptionRequest(endpoint, NOW.plusSeconds(3600).toEpochMilli(),
                new PushKeys(P256DH, AUTH));
    }

    private Identity identity(String email, UserRole role) {
        long userId = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, enabled, auth_version, created_at, updated_at)
                VALUES (?, 'test', 'hash', ?, true, 1, ?, ?) RETURNING id
                """, Long.class, email, role.name(), Timestamp.from(NOW), Timestamp.from(NOW));
        UUID sid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions
                    (sid, user_id, current_refresh_hash, created_at, expires_at, revoked_at, auth_version)
                VALUES (?, ?, ?, ?, ?, NULL, 1)
                """, sid, userId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(7200)));
        return new Identity(userId, sid, role);
    }

    private record Identity(long userId, UUID sid, UserRole role) {
        AuthPrincipal principal() {
            return new AuthPrincipal(userId, role, sid, NOW.plusSeconds(7200));
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
