package com.example.monitoring.database.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DatabaseEncryptionKeySet {

    private final ObjectMapper objectMapper;
    private final String configuredKeys;
    private final String configuredActiveVersion;
    private Map<Integer, SecretKeySpec> keys;
    private int activeVersion;

    public DatabaseEncryptionKeySet(ObjectMapper objectMapper,
                                    @Value("${app.database-security.encryption-keys}") String configuredKeys,
                                    @Value("${app.database-security.active-key-version}") String configuredActiveVersion) {
        this.objectMapper = objectMapper;
        this.configuredKeys = configuredKeys;
        this.configuredActiveVersion = configuredActiveVersion;
    }

    @PostConstruct
    void validate() {
        if (configuredKeys == null || configuredKeys.isBlank()) {
            throw new IllegalStateException("DB_CONFIG_ENCRYPTION_KEYS is required.");
        }
        try {
            activeVersion = Integer.parseInt(configuredActiveVersion);
            Map<String, String> encoded = objectMapper.readValue(
                    configuredKeys, new TypeReference<Map<String, String>>() { });
            Map<Integer, SecretKeySpec> decoded = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : encoded.entrySet()) {
                int version = Integer.parseInt(entry.getKey());
                if (version < 1) throw new IllegalStateException("Encryption key version must be positive.");
                byte[] key = Base64.getDecoder().decode(entry.getValue());
                if (key.length != 32) {
                    throw new IllegalStateException("Database encryption key must be exactly 32 bytes: " + version);
                }
                decoded.put(version, new SecretKeySpec(key, "AES"));
            }
            if (!decoded.containsKey(activeVersion)) {
                throw new IllegalStateException("DB_CONFIG_ACTIVE_KEY_VERSION is not configured in the key set.");
            }
            keys = Map.copyOf(decoded);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "DB_CONFIG_ENCRYPTION_KEYS must be JSON version-to-base64 and active version must be numeric.", exception);
        }
    }

    public int activeVersion() {
        return activeVersion;
    }

    public SecretKeySpec key(int version) {
        SecretKeySpec key = keys.get(version);
        if (key == null) throw new IllegalStateException("Unknown database encryption key version: " + version);
        return new SecretKeySpec(key.getEncoded(), "AES");
    }
}
