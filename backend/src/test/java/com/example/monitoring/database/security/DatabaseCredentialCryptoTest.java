package com.example.monitoring.database.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCredentialCryptoTest {

    private DatabaseCredentialCrypto crypto;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.US_ASCII));
        DatabaseEncryptionKeySet keys = new DatabaseEncryptionKeySet(
                new ObjectMapper(), "{\"1\":\"" + key + "\"}", "1");
        keys.validate();
        crypto = new DatabaseCredentialCrypto(new ResourceSecretCrypto(keys));
    }

    @Test
    void encryptsWithFreshNonceAndDecryptsWithMatchingAad() {
        EncryptedValue first = crypto.encrypt(12L, "password", "secret-value");
        EncryptedValue second = crypto.encrypt(12L, "password", "secret-value");

        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(crypto.decrypt(12L, "password", first.keyVersion(), first.nonce(), first.ciphertext()))
                .isEqualTo("secret-value");
    }

    @Test
    void rejectsCiphertextWhenDatabaseIdOrFieldAadChanges() {
        EncryptedValue encrypted = crypto.encrypt(12L, "username", "collector");

        assertThatThrownBy(() -> crypto.decrypt(
                13L, "username", encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(
                12L, "password", encrypted.keyVersion(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
    }
}
