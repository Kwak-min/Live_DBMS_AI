package com.example.monitoring.common.web;

import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.web.BearerAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditRequestContext {
    private final ClientIpResolver clientIpResolver;

    public Details current() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return new Details(null, "unknown", UUID.randomUUID());
        HttpServletRequest request = attributes.getRequest();
        Object principal = request.getAttribute(BearerAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return new Details(principal instanceof AuthPrincipal auth ? auth.userId() : null, clientIpResolver.resolve(request),
                requestId instanceof String value ? UUID.fromString(value) : UUID.randomUUID());
    }

    public record Details(Long actorId, String clientIp, UUID requestId) { }
}
