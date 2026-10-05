package com.example.monitoring.notification.security;

import com.example.monitoring.database.security.DatabaseEncryptionKeySet;
import com.example.monitoring.database.security.EncryptedValue;
import com.example.monitoring.database.security.ResourceSecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationSecretCodecTest {
    private NotificationSecretCodec codec;
    private ResourceSecretCrypto crypto;
    private String p256dh;
    private String auth;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        DatabaseEncryptionKeySet keys = new DatabaseEncryptionKeySet(mapper, "{\"1\":\"" + key + "\"}", "1");
        ReflectionTestUtils.invokeMethod(keys, "validate");
        crypto = new ResourceSecretCrypto(keys);
        codec = new NotificationSecretCodec(crypto, mapper, new PushEndpointPolicy(""), new SlackWebhookPolicy());
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        p256dh = encoder.encodeToString(CustomNamedCurves.getByName("secp256r1").getG().getEncoded(false));
        auth = encoder.encodeToString(new byte[16]);
    }

    @Test
    void encryptsOneBoundPushBundleAndReturnsDefensiveHash() {
        String endpoint = "https://fcm.googleapis.com/push/recipient?token=opaque%2Fvalue";
        EncryptedValue encrypted = codec.encryptPush(41L, endpoint, p256dh, auth);

        PushSecretBundle decoded = codec.decryptPush(41L, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext());
        assertThat(decoded.endpoint()).isEqualTo(endpoint);
        assertThat(decoded.p256dh()).isEqualTo(p256dh);
        assertThat(decoded.auth()).isEqualTo(auth);
        assertThat(codec.endpointHash(endpoint)).hasSize(32);
        assertThat(codec.endpointHash(endpoint))
                .isNotEqualTo(codec.endpointHash("https://fcm.googleapis.com/push/recipient?token=opaque%2fvalue"));
        assertThat(decoded.toString()).doesNotContain(endpoint, p256dh, auth);
        assertThatThrownBy(() -> codec.decryptPush(42L, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(endpoint)
                .hasMessageNotContaining(p256dh)
                .hasMessageNotContaining(auth);
    }

    @Test
    void rejectsInvalidUrlsAndDecodedKeyLengthsBeforeEncryption() {
        assertThatThrownBy(() -> codec.encryptPush(41L, "https://evil.test/push", p256dh, auth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encryptPush(41L, "https://fcm.googleapis.com/push", "AA", auth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encryptPush(41L, "https://fcm.googleapis.com/push", p256dh, "AA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encryptSlack(9L, "https://hooks.slack.com/services/T/B/X?leak=secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("leak")
                .hasMessageNotContaining("secret");
    }

    @Test
    void bindsSlackWebhookToRowAadAndNeverReturnsItFromToString() {
        String webhook = "https://hooks.slack.com/services/T/B/X";
        EncryptedValue encrypted = codec.encryptSlack(9L, webhook);

        assertThat(codec.decryptSlack(9L, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext()))
                .isEqualTo(webhook);
        assertThat(encrypted.toString()).doesNotContain(webhook);
        assertThatThrownBy(() -> codec.decryptSlack(10L, encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(webhook);
    }
}
