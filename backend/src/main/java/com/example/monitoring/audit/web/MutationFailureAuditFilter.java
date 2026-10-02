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
    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private static final Pattern USER = Pattern.compile("^/api/v1/users/(\\d+)/(role|status)$");
    private static final Pattern DATABASE = Pattern.compile("^/api/v1/databases/(\\d+)(/ping)?$");
    private static final int MAX_PART_C_SEGMENT_LENGTH = 64;
    private static final String POLICY_PREFIX = "/api/v1/databases/";
    private static final String POLICY_SUFFIX = "/risk-policy";
    private static final String PUSH_PREFIX = "/api/v1/notifications/push-subscriptions/";
    private static final String WEBHOOK_PREFIX = "/api/v1/notifications/webhooks/";
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
            log.error("Failed to persist mutation failure audit. method={}, action={}, status={}",
                    request.getMethod(), target.action(), response.getStatus(), exception);
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
        RouteSegment policy = exactSegment(path, POLICY_PREFIX, POLICY_SUFFIX);
        if ("PUT".equals(method) && policy.matches()) {
            String targetId = safePartCTargetId(policy.value());
            return new FailureTarget(AuditAction.POLICY_UPDATED, AuditTargetType.POLICY,
                    targetId, safeLong(policy.value()));
        }
        if ("POST".equals(method) && "/api/v1/notifications/push-subscriptions".equals(path)) {
            return new FailureTarget(AuditAction.PUSH_REGISTERED,
                    AuditTargetType.PUSH_SUBSCRIPTION, null, null);
        }
        RouteSegment push = exactSegment(path, PUSH_PREFIX, "");
        if ("DELETE".equals(method) && push.matches()) {
            return new FailureTarget(AuditAction.PUSH_DELETED,
                    AuditTargetType.PUSH_SUBSCRIPTION, safePartCTargetId(push.value()), null);
        }
        if ("POST".equals(method) && "/api/v1/notifications/webhooks".equals(path)) {
            return new FailureTarget(AuditAction.WEBHOOK_CREATED, AuditTargetType.WEBHOOK, null, null);
        }
        RouteSegment webhook = exactSegment(path, WEBHOOK_PREFIX, "");
        if (webhook.matches()) {
            String targetId = safePartCTargetId(webhook.value());
            if ("PATCH".equals(method)) {
                return new FailureTarget(AuditAction.WEBHOOK_UPDATED,
                        AuditTargetType.WEBHOOK, targetId, null);
            }
            if ("DELETE".equals(method)) {
                return new FailureTarget(AuditAction.WEBHOOK_DELETED,
                        AuditTargetType.WEBHOOK, targetId, null);
            }
        }
        return null;
    }

    private Long safeLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 1 && parsed <= MAX_SAFE_ID ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safeTargetId(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private String safePartCTargetId(String value) {
        return safeLong(value) == null ? null : value;
    }

    private RouteSegment exactSegment(String path, String prefix, String suffix) {
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return RouteSegment.NO_MATCH;
        }
        int start = prefix.length();
        int end = path.length() - suffix.length();
        int separator = path.indexOf('/', start);
        if (end <= start || (separator >= 0 && separator < end)) {
            return RouteSegment.NO_MATCH;
        }
        int length = end - start;
        String value = length <= MAX_PART_C_SEGMENT_LENGTH ? path.substring(start, end) : null;
        return new RouteSegment(true, value);
    }

    private record FailureTarget(AuditAction action, AuditTargetType type, String targetId, Long databaseId) { }

    private record RouteSegment(boolean matches, String value) {
        private static final RouteSegment NO_MATCH = new RouteSegment(false, null);
    }
}
