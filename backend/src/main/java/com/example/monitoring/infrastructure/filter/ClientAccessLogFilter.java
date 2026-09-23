package com.example.monitoring.infrastructure.filter;

import com.example.monitoring.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class ClientAccessLogFilter extends OncePerRequestFilter {

    private final AuditLogService auditLogService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        String clientIp = extractClientIp(request);
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

            auditLogService.logAccess(clientIp, httpMethod, requestUri, userAgent, status, executionTimeMs);
        }
    }

    public String extractClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_CLIENT_IP",
                "HTTP_X_FORWARDED_FOR"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip.trim())) {
                // If header contains multiple IPs (e.g., "client, proxy1, proxy2"), take the first one
                if (ip.contains(",")) {
                    return ip.split(",")[0].trim();
                }
                return ip.trim();
            }
        }

        return request.getRemoteAddr();
    }
}
