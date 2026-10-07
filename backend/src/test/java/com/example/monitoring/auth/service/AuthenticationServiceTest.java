package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UsedRefreshToken;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UsedRefreshTokenRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.notification.session.AuthSessionSecurity;
import com.example.monitoring.notification.session.PushSubscriptionLifecyclePort;
import com.example.monitoring.service.AuditEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private AuthSessionSecurity sessionSecurity;
    @Mock private PushSubscriptionLifecyclePort pushSubscriptions;

    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");

    private AuthenticationService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userAccountRepository, authSessionRepository, usedRefreshTokenRepository,
                passwordHashingService, refreshTokenService, accessTokenService,
                authenticationRateLimitService, auditEventService, sessionSecurity, pushSubscriptions,
                Clock.fixed(NOW, ZoneOffset.UTC), event -> { });
        user = UserAccount.builder().id(7L).email("user@example.com").displayName("user")
                .passwordHash("hash").role(UserRole.USER).enabled(true).authVersion(1L).build();
    }

    @Test
    void refreshRotatesHashAndStoresTheUsedToken() {
        UUID sid = UUID.randomUUID();
        AuthSession session = session(sid);
        when(refreshTokenService.hash("old-token")).thenReturn("old-hash");
        when(sessionSecurity.findCurrentRefreshIdentity("old-hash"))
                .thenReturn(Optional.of(new AuthSessionSecurity.SessionIdentity(sid, 7L)));
        when(sessionSecurity.lockSessionUsable(sid, 7L)).thenReturn(true);
        when(authSessionRepository.findWithUserById(sid)).thenReturn(Optional.of(session));
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
        when(sessionSecurity.findCurrentRefreshIdentity("old-hash")).thenReturn(Optional.empty());
        when(usedRefreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(used));
        when(sessionSecurity.findIdentity(sid))
                .thenReturn(Optional.of(new AuthSessionSecurity.SessionIdentity(sid, 7L)));
        when(sessionSecurity.lockSession(sid, 7L)).thenReturn(true);
        when(authSessionRepository.findWithUserById(sid)).thenReturn(Optional.of(session));

        service.logout("old-token");

        assertThat(session.getRevokedAt()).isNotNull();
        verify(auditEventService).successCurrent(any(), any(), eq(sid.toString()), isNull(), anyString());
        verify(pushSubscriptions).deactivateBySession(sid, NOW);
    }

    @Test
    void invalidRefreshRevokesSessionAndTombstonesPushInTheSameCall() {
        UUID sid = UUID.randomUUID();
        AuthSession session = session(sid);
        when(refreshTokenService.hash("expired-token")).thenReturn("old-hash");
        when(sessionSecurity.findCurrentRefreshIdentity("old-hash"))
                .thenReturn(Optional.of(new AuthSessionSecurity.SessionIdentity(sid, 7L)));
        when(sessionSecurity.lockSessionUsable(sid, 7L)).thenReturn(false);
        when(authSessionRepository.findWithUserById(sid)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.refresh("expired-token"))
                .isInstanceOf(ApiException.class);

        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        verify(pushSubscriptions).deactivateBySession(sid, NOW);
    }

    @Test
    void reusedRefreshTokenRevokesAndTombstonesItsOriginalSession() {
        UUID sid = UUID.randomUUID();
        AuthSession session = session(sid);
        UsedRefreshToken used = UsedRefreshToken.builder().tokenHash("used-hash").sessionId(sid)
                .usedAt(NOW.minusSeconds(1)).expiresAt(session.getExpiresAt()).build();
        when(refreshTokenService.hash("reused-token")).thenReturn("used-hash");
        when(sessionSecurity.findCurrentRefreshIdentity("used-hash")).thenReturn(Optional.empty());
        when(usedRefreshTokenRepository.findByTokenHash("used-hash")).thenReturn(Optional.of(used));
        when(sessionSecurity.findIdentity(sid))
                .thenReturn(Optional.of(new AuthSessionSecurity.SessionIdentity(sid, 7L)));
        when(sessionSecurity.lockSession(sid, 7L)).thenReturn(true);
        when(authSessionRepository.findWithUserById(sid)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.refresh("reused-token"))
                .isInstanceOf(ApiException.class);

        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        verify(pushSubscriptions).deactivateBySession(sid, NOW);
    }

    @Test
    void exposesTokenFreeSessionValidationAndTransactionalLockSeam() {
        UUID sid = UUID.randomUUID();
        when(sessionSecurity.isSessionUsable(sid, 7L)).thenReturn(true);
        when(sessionSecurity.lockSessionUsable(sid, 7L)).thenReturn(true);

        assertThat(service.isSessionUsable(sid, 7L)).isTrue();
        assertThat(service.lockSessionUsable(sid, 7L)).isTrue();
    }

    private AuthSession session(UUID sid) {
        return AuthSession.builder().id(sid).user(user).currentRefreshHash("old-hash")
                .createdAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(3600))
                .authVersion(1L).build();
    }
}
