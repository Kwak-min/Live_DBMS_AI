package com.example.monitoring.auth.service;

import com.example.monitoring.common.api.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class CsrfTokenService {

    public static final Duration TTL = Duration.ofHours(8);
    private static final String KEY_PREFIX = "auth:csrf:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public CsrfTokenIssue issue(String existingSession) {
        try {
            if (existingSession != null && !existingSession.isBlank()) {
                String existingToken = redisTemplate.opsForValue().get(key(existingSession));
                if (existingToken != null) {
                    return new CsrfTokenIssue(existingSession, existingToken, false);
                }
            }
            String session = randomToken();
            String token = randomToken();
            redisTemplate.opsForValue().set(key(session), token, TTL);
            return new CsrfTokenIssue(session, token, true);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public void validate(String session, String suppliedToken) {
        if (session == null || suppliedToken == null) {
            throw invalid();
        }
        try {
            String expected = redisTemplate.opsForValue().get(key(session));
            if (expected == null || !MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII), suppliedToken.getBytes(StandardCharsets.US_ASCII))) {
                throw invalid();
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public void delete(String session) {
        if (session == null || session.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key(session));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private String randomToken() {
        byte[] value = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String key(String session) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(session.getBytes(StandardCharsets.US_ASCII));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.FORBIDDEN, "CSRF_INVALID", "CSRF token이 유효하지 않습니다.");
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "인증 저장소를 사용할 수 없습니다.");
    }

    public record CsrfTokenIssue(String session, String token, boolean newSession) {
    }
}
