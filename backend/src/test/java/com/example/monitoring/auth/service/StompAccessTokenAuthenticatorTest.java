package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StompAccessTokenAuthenticatorTest {
    @Test
    void acceptsBearerAndReturnsSocketPrincipal() {
        AuthService authService = mock(AuthService.class);
        UUID sid = UUID.randomUUID();
        when(authService.authenticate("token")).thenReturn(new AuthPrincipal(7L, UserRole.ADMIN, sid, Instant.now().plusSeconds(60)));
        StompPrincipal principal = new StompAccessTokenAuthenticator(authService).authenticateAuthorization("Bearer token");
        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.sessionId()).isEqualTo(sid);
        verify(authService).authenticate("token");
    }

    @Test
    void rejectsMissingBearerHeader() {
        StompAccessTokenAuthenticator authenticator = new StompAccessTokenAuthenticator(mock(AuthService.class));
        assertThatThrownBy(() -> authenticator.authenticateAuthorization(null)).isInstanceOf(ApiException.class);
    }
}
