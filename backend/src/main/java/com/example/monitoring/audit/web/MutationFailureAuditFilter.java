package com.example.monitoring.audit.web;

import com.example.monitoring.domain.*;
import com.example.monitoring.service.AuditEventService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class MutationFailureAuditFilter extends OncePerRequestFilter {
    private static final Pattern USER = Pattern.compile("^/api/v1/users/(\\d+)/(role|status)$");
    private static final Pattern DATABASE = Pattern.compile("^/api/v1/databases/(\\d+)(/ping)?$");
    private final AuditEventService auditEventService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        if (response.getStatus() < 400) return;
        FailureTarget target = classify(request.getMethod(), request.getRequestURI());
        if (target == null) return;
        try {
            auditEventService.failureCurrent(target.action(), target.type(), target.targetId(), target.databaseId(),
                    "Request rejected with HTTP " + response.getStatus());
        } catch (RuntimeException exception) {
            log.error("Failed to persist mutation failure audit. method={}, path={}, status={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), exception);
        }
    }

    private FailureTarget classify(String method, String path) {
        if ("POST".equals(method) && "/api/v1/auth/signup".equals(path))
            return new FailureTarget(AuditAction.USER_SIGNUP, AuditTargetType.USER, null, null);
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path))
            return new FailureTarget(AuditAction.USER_LOGIN, AuditTargetType.USER, null, null);
        if ("POST".equals(method) && "/api/v1/auth/logout".equals(path))
            return new FailureTarget(AuditAction.USER_LOGOUT, AuditTargetType.SESSION, null, null);
        Matcher user = USER.matcher(path);
        if ("PATCH".equals(method) && user.matches()) {
            AuditAction action = "role".equals(user.group(2)) ? AuditAction.USER_ROLE_CHANGED : AuditAction.USER_STATUS_CHANGED;
            return new FailureTarget(action, AuditTargetType.USER, safeTargetId(user.group(1)), null);
        }
        if ("POST".equals(method) && "/api/v1/databases".equals(path))
            return new FailureTarget(AuditAction.DATABASE_CREATED, AuditTargetType.DATABASE, null, null);
        Matcher database = DATABASE.matcher(path);
        if (database.matches()) {
            String targetId = safeTargetId(database.group(1));
            Long id = safeLong(database.group(1));
            if ("PATCH".equals(method)) return new FailureTarget(AuditAction.DATABASE_UPDATED, AuditTargetType.DATABASE, targetId, id);
            if ("DELETE".equals(method)) return new FailureTarget(AuditAction.DATABASE_DELETED, AuditTargetType.DATABASE, targetId, id);
            if ("POST".equals(method) && database.group(2) != null)
                return new FailureTarget(AuditAction.DATABASE_PING, AuditTargetType.DATABASE, targetId, id);
        }
        return null;
    }

    private Long safeLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safeTargetId(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private record FailureTarget(AuditAction action, AuditTargetType type, String targetId, Long databaseId) { }
}
