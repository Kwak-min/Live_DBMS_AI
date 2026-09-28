package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserRole;

import java.time.Instant;
import java.util.UUID;

public record AuthPrincipal(
        Long userId,
        UserRole role,
        UUID sessionId,
        Instant expiresAt
) {
}
