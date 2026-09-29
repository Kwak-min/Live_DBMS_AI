package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UsedRefreshToken;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UsedRefreshTokenRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.service.AuditEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private AuthSessionRepository authSessionRepository;
    @Mock private UsedRefreshTokenRepository usedRefreshTokenRepository;
    @Mock private PasswordHashingService passwordHashingService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AccessTokenService accessTokenService;
    @Mock private AuthenticationRateLimitService authenticationRateLimitService;
    @Mock private AuditEventService auditEventService;

    private AuthenticationService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userAccountRepository, authSessionRepository, usedRefreshTokenRepository,
                passwordHashingService, refreshTokenService, accessTokenService,
                authenticationRateLimitService, auditEventService);
        user = UserAccount.builder().id(7L).email("user@example.com").displayName("user")
                .passwordHash("hash").role(UserRole.USER).enabled(true).authVersion(1L).build();
    }

    @Test
    void refreshRotatesHashAndStoresTheUsedToken() {
        UUID sid = UUID.randomUUID();
        AuthSession session = session(sid);
        when(refreshTokenService.hash("old-token")).thenReturn("old-hash");
        when(authSessionRepository.findByCurrentRefreshHashForUpdate("old-hash"))
                .thenReturn(Optional.of(session));
        when(refreshTokenService.generate()).thenReturn("new-token");
        when(refreshTokenService.hash("new-token")).thenReturn("new-hash");
        when(accessTokenService.issue(user, sid)).thenReturn(
                new AccessTokenService.IssuedAccessToken("access-token", Instant.now().plusSeconds(900)));

        AuthenticationService.AuthenticationResult result = service.refresh("old-token");

        assertThat(result.refreshToken()).isEqualTo("new-token");
        assertThat(session.getCurrentRefreshHash()).isEqualTo("new-hash");
        ArgumentCaptor<UsedRefreshToken> used = ArgumentCaptor.forClass(UsedRefreshToken.class);
        verify(usedRefreshTokenRepository).save(used.capture());
        assertThat(used.getValue().getSessionId()).isEqualTo(sid);
        verify(authenticationRateLimitService).checkRefresh(sid);
    }

    @Test
    void logoutWithPreviouslyRotatedTokenRevokesItsSession() {
        UUID sid = UUID.randomUUID();
        AuthSession session = session(sid);
        UsedRefreshToken used = UsedRefreshToken.builder().tokenHash("old-hash").sessionId(sid)
                .usedAt(Instant.now()).expiresAt(session.getExpiresAt()).build();
        when(refreshTokenService.hash("old-token")).thenReturn("old-hash");
        when(authSessionRepository.findByCurrentRefreshHashForUpdate("old-hash")).thenReturn(Optional.empty());
        when(usedRefreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(used));
        when(authSessionRepository.findByIdForUpdate(sid)).thenReturn(Optional.of(session));

        service.logout("old-token");

        assertThat(session.getRevokedAt()).isNotNull();
        verify(auditEventService).successCurrent(any(), any(), eq(sid.toString()), isNull(), anyString());
    }

    private AuthSession session(UUID sid) {
        return AuthSession.builder().id(sid).user(user).currentRefreshHash("old-hash")
                .createdAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(3600))
                .authVersion(1L).build();
    }
}
