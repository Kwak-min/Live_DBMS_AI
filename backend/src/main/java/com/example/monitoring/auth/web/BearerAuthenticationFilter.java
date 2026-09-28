package com.example.monitoring.auth.web;

import com.example.monitoring.auth.service.AuthPrincipal;
import com.example.monitoring.auth.service.AuthService;
import com.example.monitoring.common.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Component
@RequiredArgsConstructor
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    public static final String PRINCIPAL_ATTRIBUTE = BearerAuthenticationFilter.class.getName() + ".principal";

    private final AuthService authService;
    private final ApiSecurityErrorWriter errorWriter;
    private final Environment environment;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicAuthPath(path)) return true;
        if (path.startsWith("/api/")) return false;
        boolean protectedOperationsPath = path.equals("/actuator/info") || path.equals("/actuator/metrics")
                || path.startsWith("/actuator/metrics/") || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/") || path.startsWith("/swagger-ui");
        return !protectedOperationsPath || environment.acceptsProfiles(Profiles.of("local"));
    }

    private boolean isPublicAuthPath(String path) {
        return path.equals("/api/v1/auth/csrf")
                || path.equals("/api/v1/auth/signup")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX) || header.length() == BEARER_PREFIX.length()) {
            errorWriter.write(request, response,
                    new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "인증이 필요합니다."));
            return;
        }
        try {
            AuthPrincipal principal = authService.authenticate(header.substring(BEARER_PREFIX.length()));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response, exception);
        }
    }
}
