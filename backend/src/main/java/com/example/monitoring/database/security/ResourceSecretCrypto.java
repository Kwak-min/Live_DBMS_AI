package com.example.monitoring.database.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Part B's shared at-rest encryption primitive. Part C owns notification persistence,
 * but uses this service for Slack URLs and Web Push endpoint/key material.
 */
@Service
@RequiredArgsConstructor
public class ResourceSecretCrypto {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String AAD_PART = "[a-z][a-z0-9_-]{0,63}";

    private final DatabaseEncryptionKeySet keySet;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedValue encrypt(String resourceType, long resourceId, String fieldName, String plaintext) {
        validateAad(resourceType, resourceId, fieldName);
        if (plaintext == null) throw new IllegalArgumentException("Secret plaintext is required.");
        int version = keySet.activeVersion();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySet.key(version), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(resourceType, resourceId, fieldName));
            return new EncryptedValue(version, nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt resource secret.", exception);
        }
    }

    public String decrypt(String resourceType, long resourceId, String fieldName, int keyVersion,
                          byte[] nonce, byte[] ciphertext) {
        validateAad(resourceType, resourceId, fieldName);
        if (nonce == null || nonce.length != NONCE_BYTES || ciphertext == null || ciphertext.length < 16) {
            throw new IllegalStateException("Invalid encrypted resource secret format.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySet.key(keyVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(resourceType, resourceId, fieldName));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt resource secret.", exception);
        }
    }

    private void validateAad(String resourceType, long resourceId, String fieldName) {
        if (resourceId < 1 || resourceType == null || !resourceType.matches(AAD_PART)
                || fieldName == null || !fieldName.matches(AAD_PART)) {
            throw new IllegalArgumentException("Invalid resource secret AAD components.");
        }
    }

    private byte[] aad(String resourceType, long resourceId, String fieldName) {
        return (resourceType + ':' + resourceId + ':' + fieldName).getBytes(StandardCharsets.UTF_8);
    }
}
