package com.example.monitoring.auth.service;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Implements the Part B Argon2id policy. Callers must validate before hashing;
 * the raw password is never trimmed, normalized, logged, or persisted.
 */
@Service
public class PasswordHashingService {

    private static final int MIN_CODE_POINTS = 8;
    private static final int MAX_CODE_POINTS = 128;
    private static final int MAX_UTF8_BYTES = 1024;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(
            16, 32, 1, 19_456, 2);

    public String hash(String rawPassword) {
        validate(rawPassword);
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null && encoder.matches(rawPassword, encodedPassword);
    }

    public void validate(String rawPassword) {
        if (rawPassword == null) {
            throw new PasswordPolicyException("비밀번호는 필수입니다.");
        }
        int codePoints = rawPassword.codePointCount(0, rawPassword.length());
        if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
            throw new PasswordPolicyException("비밀번호는 8~128자여야 합니다.");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new PasswordPolicyException("비밀번호는 UTF-8 기준 1024바이트 이하여야 합니다.");
        }
    }

    public static class PasswordPolicyException extends IllegalArgumentException {
        public PasswordPolicyException(String message) {
            super(message);
        }
    }
}
