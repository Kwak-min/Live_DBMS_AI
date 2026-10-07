package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.AuthSession;
import com.example.monitoring.auth.domain.UsedRefreshToken;
import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.dto.LoginRequest;
import com.example.monitoring.auth.dto.TokenResponse;
import com.example.monitoring.auth.dto.UserResponse;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UsedRefreshTokenRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.notification.session.AuthSessionSecurity;
import com.example.monitoring.notification.session.PushSubscriptionLifecyclePort;
import com.example.monitoring.service.AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthenticationService implements AuthService {

    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final UserAccountRepository userAccountRepository;
    private final AuthSessionRepository authSessionRepository;
    private final UsedRefreshTokenRepository usedRefreshTokenRepository;
    private final PasswordHashingService passwordHashingService;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenService accessTokenService;
    private final AuthenticationRateLimitService authenticationRateLimitService;
    private final AuditEventService auditEventService;
    private final AuthSessionSecurity sessionSecurity;
    private final PushSubscriptionLifecyclePort pushSubscriptions;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional
    public AuthenticationResult login(LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        UserAccount user = userAccountRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw invalidCredentials();
        }
        if (!user.isEnabled() || !passwordHashingService.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        Instant now = clock.instant();
        String refreshToken = refreshTokenService.generate();
        AuthSession session = authSessionRepository.save(AuthSession.builder()
                .id(UUID.randomUUID())
                .user(user)
                .currentRefreshHash(refreshTokenService.hash(refreshToken))
                .createdAt(now)
                .expiresAt(now.plus(REFRESH_TTL))
                .authVersion(user.getAuthVersion())
                .build());
        auditEventService.successCurrent(AuditAction.USER_LOGIN, AuditTargetType.SESSION, session.getId().toString(), null, "User login succeeded");
        return result(user, session, refreshToken);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthenticationResult refresh(String rawRefreshToken) {
        String tokenHash = refreshTokenService.hash(rawRefreshToken);
        if (tokenHash == null) {
            throw invalidRefresh();
        }

        AuthSessionSecurity.SessionIdentity identity = sessionSecurity.findCurrentRefreshIdentity(tokenHash).orElse(null);
        if (identity == null) {
            revokeReusedSession(tokenHash);
            throw invalidRefresh();
        }
        boolean usable = sessionSecurity.lockSessionUsable(identity.sessionId(), identity.userId());
        AuthSession session = authSessionRepository.findWithUserById(identity.sessionId()).orElse(null);
        if (session == null || !tokenHash.equals(session.getCurrentRefreshHash())) {
            revokeLockedSession(session);
            throw invalidRefresh();
        }
        authenticationRateLimitService.checkRefresh(session.getId());
        Instant now = now();
        UserAccount user = session.getUser();
        if (!usable) {
            session.revoke(now);
            pushSubscriptions.deactivateBySession(session.getId(), now);
            events.publishEvent(AuthSessionsRevokedEvent.session(session.getId()));
            throw invalidRefresh();
        }

        String nextRefreshToken = refreshTokenService.generate();
        usedRefreshTokenRepository.save(UsedRefreshToken.builder()
                .tokenHash(tokenHash)
                .sessionId(session.getId())
                .usedAt(now)
                .expiresAt(session.getExpiresAt())
                .build());
        session.rotateRefreshHash(refreshTokenService.hash(nextRefreshToken));
        return result(user, session, nextRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = refreshTokenService.hash(rawRefreshToken);
        if (tokenHash == null) {
            return;
        }
        AuthSessionSecurity.SessionIdentity identity = sessionSecurity.findCurrentRefreshIdentity(tokenHash)
                .or(() -> usedRefreshTokenRepository.findByTokenHash(tokenHash)
                        .flatMap(used -> sessionSecurity.findIdentity(used.getSessionId())))
                .orElse(null);
        if (identity == null || !sessionSecurity.lockSession(identity.sessionId(), identity.userId())) {
            return;
        }
        AuthSession session = authSessionRepository.findWithUserById(identity.sessionId()).orElse(null);
        boolean matchesCurrent = session != null && tokenHash.equals(session.getCurrentRefreshHash());
        boolean matchesUsed = usedRefreshTokenRepository.findByTokenHash(tokenHash)
                .map(used -> used.getSessionId().equals(identity.sessionId())).orElse(false);
        if (session == null || (!matchesCurrent && !matchesUsed)) {
            return;
        }
        Instant now = now();
        if (session.getRevokedAt() == null) {
            session.revoke(now);
            auditEventService.successCurrent(AuditAction.USER_LOGOUT, AuditTargetType.SESSION,
                    session.getId().toString(), null, "User logout succeeded");
        }
        pushSubscriptions.deactivateBySession(session.getId(), now);
        events.publishEvent(AuthSessionsRevokedEvent.session(session.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String accessToken) {
        AccessTokenService.VerifiedAccessToken token = accessTokenService.verify(accessToken);
        AuthSession session = authSessionRepository.findWithUserById(token.sessionId())
                .orElseThrow(this::sessionRevoked);
        UserAccount user = session.getUser();
        Instant now = now();
        if (!session.isUsableAt(now) || !user.isEnabled()
                || user.getId().longValue() != token.userId()
                || user.getAuthVersion() != token.authVersion()
                || session.getAuthVersion() != token.authVersion()
                || !user.getRole().name().equals(token.role())) {
            throw sessionRevoked();
        }
        return new AuthPrincipal(user.getId(), UserRole.valueOf(token.role()), session.getId(), token.expiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSessionUsable(UUID sessionId, long userId) {
        return sessionSecurity.isSessionUsable(sessionId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockSessionUsable(UUID sessionId, long userId) {
        return sessionSecurity.lockSessionUsable(sessionId, userId);
    }

    private AuthenticationResult result(UserAccount user, AuthSession session, String refreshToken) {
        AccessTokenService.IssuedAccessToken accessToken = accessTokenService.issue(user, session.getId());
        TokenResponse response = new TokenResponse(
                accessToken.value(), TokenResponse.BEARER, TokenResponse.EXPIRES_IN_SECONDS, UserResponse.from(user));
        return new AuthenticationResult(response, refreshToken, session.getExpiresAt());
    }

    private void revokeReusedSession(String tokenHash) {
        usedRefreshTokenRepository.findByTokenHash(tokenHash)
                .flatMap(used -> sessionSecurity.findIdentity(used.getSessionId()))
                .ifPresent(identity -> {
                    if (sessionSecurity.lockSession(identity.sessionId(), identity.userId())) {
                        authSessionRepository.findWithUserById(identity.sessionId()).ifPresent(this::revokeLockedSession);
                    }
                });
    }

    private void revokeLockedSession(AuthSession session) {
        if (session == null) {
            return;
        }
        Instant now = now();
        session.revoke(now);
        pushSubscriptions.deactivateBySession(session.getId(), now);
        events.publishEvent(AuthSessionsRevokedEvent.session(session.getId()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    private ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "Refresh token이 유효하지 않습니다.");
    }

    private ApiException sessionRevoked() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "인증 세션이 만료되었거나 폐기되었습니다.");
    }

    public record AuthenticationResult(TokenResponse response, String refreshToken, Instant sessionExpiresAt) {
    }
}
