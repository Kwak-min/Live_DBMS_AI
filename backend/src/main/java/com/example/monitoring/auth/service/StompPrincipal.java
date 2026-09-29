package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserRole;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public record StompPrincipal(Long userId, UserRole role, UUID sessionId, Instant expiresAt) implements Principal {
    @Override public String getName() { return userId.toString(); }

    public static StompPrincipal from(AuthPrincipal value) {
        return new StompPrincipal(value.userId(), value.role(), value.sessionId(), value.expiresAt());
    }
}
