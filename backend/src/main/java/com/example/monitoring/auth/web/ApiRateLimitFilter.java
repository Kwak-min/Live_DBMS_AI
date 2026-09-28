package com.example.monitoring.auth.web;

import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.common.api.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens')) or 60000
            local last = tonumber(redis.call('HGET', KEYS[1], 'last')) or now
            tokens = math.min(60000, tokens + math.max(0, now - last) * 30)
            local retry = 0
            if tokens >= 1000 then tokens = tokens - 1000
            else retry = math.ceil((1000 - tokens) / 30) end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last', now)
            redis.call('PEXPIRE', KEYS[1], 120000)
            return retry
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ApiSecurityErrorWriter errorWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || isSpecialAuthPath(request.getRequestURI())
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private boolean isSpecialAuthPath(String path) {
        return path.equals("/api/v1/auth/csrf") || path.equals("/api/v1/auth/signup")
                || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object value = request.getAttribute(BearerAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof AuthPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Long retryMs = redisTemplate.execute(TOKEN_BUCKET,
                    List.of("rate-limit:api:user:" + principal.userId()), Long.toString(System.currentTimeMillis()));
            if (retryMs == null) throw new IllegalStateException("Redis rate-limit script returned null");
            if (retryMs > 0) {
                long retrySeconds = Math.max(1L, (retryMs + 999L) / 1000L);
                errorWriter.write(request, response, new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", List.of(),
                        Map.of("Retry-After", Long.toString(retrySeconds))));
                return;
            }
        } catch (DataAccessException | IllegalStateException exception) {
            if (!"GET".equalsIgnoreCase(request.getMethod())) {
                errorWriter.write(request, response, new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "DEPENDENCY_UNAVAILABLE", "요청 제한 저장소를 사용할 수 없습니다."));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
