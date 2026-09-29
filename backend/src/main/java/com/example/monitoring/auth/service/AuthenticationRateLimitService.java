package com.example.monitoring.auth.service;

import com.example.monitoring.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.example.monitoring.common.web.ClientIpResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Redis-backed, fixed-window limits for credential-changing authentication requests. */
@Service
@RequiredArgsConstructor
public class AuthenticationRateLimitService {

    private static final DefaultRedisScript<String> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local remaining = redis.call('TTL', KEYS[1])
            return tostring(current) .. ':' .. tostring(remaining)
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ClientIpResolver clientIpResolver;

    public void checkLogin(String normalizedEmail, HttpServletRequest request) {
        check("login-email", normalizedEmail, 10, 15 * 60);
        check("login-ip", clientIpResolver.resolve(request), 50, 15 * 60);
    }

    public void checkSignup(HttpServletRequest request) {
        check("signup-ip", clientIpResolver.resolve(request), 10, 60 * 60);
    }

    public void checkRefresh(UUID sessionId) {
        check("refresh-sid", sessionId.toString(), 30, 60);
    }

    private void check(String scope, String subject, int limit, int windowSeconds) {
        String key = "rate-limit:auth:" + scope + ':' + sha256(subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT));
        try {
            String raw = redisTemplate.execute(INCREMENT_WITH_EXPIRY, List.of(key), Integer.toString(windowSeconds));
            if (raw == null || !raw.contains(":")) {
                throw unavailable(null);
            }
            String[] result = raw.split(":", 2);
            long count = Long.parseLong(result[0]);
            long retryAfter = Math.max(1L, Long.parseLong(result[1]));
            if (count > limit) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                        List.of(), Map.of("Retry-After", Long.toString(retryAfter)));
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException | NumberFormatException exception) {
            throw unavailable(exception);
        }
    }

    private ApiException unavailable(Exception cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
