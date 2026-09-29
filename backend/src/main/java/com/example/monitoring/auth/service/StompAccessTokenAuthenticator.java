package com.example.monitoring.auth.service;

import com.example.monitoring.common.api.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Part C calls this from its STOMP CONNECT and periodic session revalidation interceptor. */
@Service
@RequiredArgsConstructor
public class StompAccessTokenAuthenticator {
    private static final String PREFIX = "Bearer ";
    private final AuthService authService;

    public StompPrincipal authenticateAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith(PREFIX) || authorization.length() == PREFIX.length()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "STOMP Access Token이 필요합니다.");
        }
        return StompPrincipal.from(authService.authenticate(authorization.substring(PREFIX.length())));
    }
}
