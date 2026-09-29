package com.example.monitoring.infrastructure.filter;

import com.example.monitoring.service.AuditLogService;
import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.web.BearerAuthenticationFilter;
import com.example.monitoring.common.web.RequestIdFilter;
import com.example.monitoring.common.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ClientAccessLogFilter extends OncePerRequestFilter {

    private final AuditLogService auditLogService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        String clientIp = clientIpResolver.resolve(request);
        String httpMethod = request.getMethod();
        String requestUri = request.getRequestURI();
        String userAgent = request.getHeader("User-Agent");

        try {
            filterChain.doFilter(request, response);
        } finally {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            int status = response.getStatus();

            log.debug("Access log: IP={}, Method={}, URI={}, Status={}, Latency={}ms",
                    clientIp, httpMethod, requestUri, status, executionTimeMs);

            Object principal = request.getAttribute(BearerAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
            Long actorId = principal instanceof AuthPrincipal auth ? auth.userId() : null;
            UUID correlationId = requestId instanceof String value ? UUID.fromString(value) : UUID.randomUUID();
            try {
                auditLogService.logAccess(actorId, clientIp, httpMethod, limit(requestUri, 500), status,
                        executionTimeMs, correlationId);
            } catch (RuntimeException exception) {
                // Access logging is diagnostic and must never replace the actual API response.
                log.error("Failed to persist access log. requestId={}", correlationId, exception);
            }
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    public String extractClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
