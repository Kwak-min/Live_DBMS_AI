package com.example.monitoring.database.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DatabaseCredentialCrypto {
    private final ResourceSecretCrypto resourceSecretCrypto;

    public EncryptedValue encrypt(long databaseId, String fieldName, String plaintext) {
        validateField(fieldName);
        return resourceSecretCrypto.encrypt("database", databaseId, fieldName, plaintext);
    }

    public String decrypt(long databaseId, String fieldName, int keyVersion, byte[] nonce, byte[] ciphertext) {
        validateField(fieldName);
        return resourceSecretCrypto.decrypt("database", databaseId, fieldName, keyVersion, nonce, ciphertext);
    }

    private void validateField(String fieldName) {
        if (!"username".equals(fieldName) && !"password".equals(fieldName)) {
            throw new IllegalArgumentException("Unsupported database credential field.");
        }
    }
}
