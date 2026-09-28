package com.example.monitoring.database.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceSecretCryptoTest {
    private ResourceSecretCrypto crypto;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        DatabaseEncryptionKeySet keys = new DatabaseEncryptionKeySet(
                new ObjectMapper(), "{\"1\":\"" + key + "\"}", "1");
        keys.validate();
        crypto = new ResourceSecretCrypto(keys);
    }

    @Test
    void supportsNotificationSecretsWithFreshNoncesAndBoundAad() {
        EncryptedValue encrypted = crypto.encrypt("notification_webhook", 9L, "url", "https://hooks.slack.com/secret");
        assertThat(crypto.decrypt("notification_webhook", 9L, "url", encrypted.keyVersion(),
                encrypted.nonce(), encrypted.ciphertext())).isEqualTo("https://hooks.slack.com/secret");
        assertThatThrownBy(() -> crypto.decrypt("notification_webhook", 10L, "url", encrypted.keyVersion(),
                encrypted.nonce(), encrypted.ciphertext())).isInstanceOf(IllegalStateException.class);
    }
}
