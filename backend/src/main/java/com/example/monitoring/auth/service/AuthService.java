package com.example.monitoring.auth.service;

import java.util.UUID;

public interface AuthService {

    AuthPrincipal authenticate(String accessToken);

    boolean isSessionUsable(UUID sessionId, long userId);

    boolean lockSessionUsable(UUID sessionId, long userId);
}
