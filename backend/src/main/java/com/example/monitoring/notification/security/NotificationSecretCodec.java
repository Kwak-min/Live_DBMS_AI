package com.example.monitoring.notification.security;

import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.database.security.ResourceSecretCrypto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public final class NotificationSecretCodec {
    private static final String PUSH_RESOURCE = "push_subscription";
    private static final String PUSH_FIELD = "payload";
    private static final String SLACK_RESOURCE = "notification_webhook";
    private static final String SLACK_FIELD = "url";

    private final ResourceSecretCrypto crypto;
    private final ObjectMapper objectMapper;
    private final PushEndpointPolicy pushEndpointPolicy;
    private final SlackWebhookPolicy slackWebhookPolicy;

    public NotificationSecretCodec(ResourceSecretCrypto crypto, ObjectMapper objectMapper,
                                   PushEndpointPolicy pushEndpointPolicy, SlackWebhookPolicy slackWebhookPolicy) {
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.pushEndpointPolicy = pushEndpointPolicy;
        this.slackWebhookPolicy = slackWebhookPolicy;
    }

    public EncryptedValue encryptPush(long rowId, String endpoint, String p256dh, String auth) {
        PushSecretBundle validated = validatePush(endpoint, p256dh, auth);
        try {
            String plaintext = objectMapper.writeValueAsString(new PushJson(
                    validated.endpoint(), validated.p256dh(), validated.auth()));
            return crypto.encrypt(PUSH_RESOURCE, rowId, PUSH_FIELD, plaintext);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode notification secret.", exception);
        }
    }

    public PushSecretBundle decryptPush(long rowId, int keyVersion, byte[] nonce, byte[] ciphertext) {
        try {
            String plaintext = crypto.decrypt(PUSH_RESOURCE, rowId, PUSH_FIELD, keyVersion, nonce, ciphertext);
            PushJson decoded = objectMapper.readValue(plaintext, PushJson.class);
            return validatePush(decoded.endpoint(), decoded.p256dh(), decoded.auth());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decode notification secret.", exception);
        }
    }

    public EncryptedValue encryptSlack(long rowId, String webhookUrl) {
        slackWebhookPolicy.validate(webhookUrl);
        return crypto.encrypt(SLACK_RESOURCE, rowId, SLACK_FIELD, webhookUrl);
    }

    public String decryptSlack(long rowId, int keyVersion, byte[] nonce, byte[] ciphertext) {
        try {
            String webhookUrl = crypto.decrypt(SLACK_RESOURCE, rowId, SLACK_FIELD, keyVersion, nonce, ciphertext);
            slackWebhookPolicy.validate(webhookUrl);
            return webhookUrl;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decode notification secret.", exception);
        }
    }

    public byte[] endpointHash(String endpoint) {
        pushEndpointPolicy.validate(endpoint);
        try {
            return MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private PushSecretBundle validatePush(String endpoint, String p256dh, String auth) {
        pushEndpointPolicy.validate(endpoint);
        byte[] publicKey = decodeBase64Url(p256dh);
        byte[] authSecret = decodeBase64Url(auth);
        if (publicKey.length != 65 || publicKey[0] != 0x04 || authSecret.length != 16) {
            throw new IllegalArgumentException("Invalid Web Push subscription key material.");
        }
        try {
            ECPoint point = CustomNamedCurves.getByName("secp256r1").getCurve().decodePoint(publicKey);
            if (point.isInfinity()) {
                throw new IllegalArgumentException("Invalid Web Push subscription key material.");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Web Push subscription key material.");
        }
        return new PushSecretBundle(endpoint, p256dh, auth);
    }

    private byte[] decodeBase64Url(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Web Push subscription key material.");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Web Push subscription key material.");
        }
    }

    private record PushJson(String endpoint, String p256dh, String auth) { }
}
