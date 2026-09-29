package com.example.monitoring.auth.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtKeySet {

    private final ObjectMapper objectMapper;
    private final String configuredKeys;
    private final String activeKeyId;
    private Map<String, byte[]> keys;

    public JwtKeySet(ObjectMapper objectMapper,
                     @Value("${app.auth.jwt-signing-keys}") String configuredKeys,
                     @Value("${app.auth.jwt-active-kid}") String activeKeyId) {
        this.objectMapper = objectMapper;
        this.configuredKeys = configuredKeys;
        this.activeKeyId = activeKeyId;
    }

    @PostConstruct
    void validate() {
        if (configuredKeys == null || configuredKeys.isBlank()) {
            throw new IllegalStateException("JWT_SIGNING_KEYS is required.");
        }
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("JWT_ACTIVE_KID is required.");
        }
        try {
            Map<String, String> encodedKeys = objectMapper.readValue(
                    configuredKeys, new TypeReference<Map<String, String>>() { });
            Map<String, byte[]> decodedKeys = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : encodedKeys.entrySet()) {
                if (entry.getKey().isBlank()) {
                    throw new IllegalStateException("JWT key id must not be blank.");
                }
                byte[] decoded = Base64.getDecoder().decode(entry.getValue());
                if (decoded.length < 32) {
                    throw new IllegalStateException("JWT signing key must decode to at least 32 bytes: " + entry.getKey());
                }
                decodedKeys.put(entry.getKey(), decoded.clone());
            }
            if (!decodedKeys.containsKey(activeKeyId)) {
                throw new IllegalStateException("JWT_ACTIVE_KID does not exist in JWT_SIGNING_KEYS.");
            }
            this.keys = Map.copyOf(decodedKeys);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("JWT_SIGNING_KEYS must be a JSON object of kid to base64 secret.", exception);
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public byte[] activeKey() {
        return key(activeKeyId);
    }

    public byte[] key(String keyId) {
        byte[] key = keys.get(keyId);
        return key == null ? null : key.clone();
    }
}
