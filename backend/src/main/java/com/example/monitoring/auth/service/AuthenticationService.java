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
import com.example.monitoring.service.AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Clock clock = Clock.systemUTC();

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

        AuthSession session = authSessionRepository.findByCurrentRefreshHashForUpdate(tokenHash).orElse(null);
        if (session == null) {
            revokeReusedSession(tokenHash);
            throw invalidRefresh();
        }
        authenticationRateLimitService.checkRefresh(session.getId());
        Instant now = clock.instant();
        UserAccount user = session.getUser();
        if (!session.isUsableAt(now) || !user.isEnabled()
                || session.getAuthVersion() != user.getAuthVersion()) {
            session.revoke(now);
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
        AuthSession session = authSessionRepository.findByCurrentRefreshHashForUpdate(tokenHash).orElse(null);
        if (session == null) {
            session = usedRefreshTokenRepository.findByTokenHash(tokenHash)
                    .flatMap(used -> authSessionRepository.findByIdForUpdate(used.getSessionId()))
                    .orElse(null);
        }
        if (session != null && session.getRevokedAt() == null) {
            session.revoke(clock.instant());
            auditEventService.successCurrent(AuditAction.USER_LOGOUT, AuditTargetType.SESSION,
                    session.getId().toString(), null, "User logout succeeded");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String accessToken) {
        AccessTokenService.VerifiedAccessToken token = accessTokenService.verify(accessToken);
        AuthSession session = authSessionRepository.findWithUserById(token.sessionId())
                .orElseThrow(this::sessionRevoked);
        UserAccount user = session.getUser();
        Instant now = clock.instant();
        if (!session.isUsableAt(now) || !user.isEnabled()
                || user.getId().longValue() != token.userId()
                || user.getAuthVersion() != token.authVersion()
                || session.getAuthVersion() != token.authVersion()
                || !user.getRole().name().equals(token.role())) {
            throw sessionRevoked();
        }
        return new AuthPrincipal(user.getId(), UserRole.valueOf(token.role()), session.getId(), token.expiresAt());
    }

    private AuthenticationResult result(UserAccount user, AuthSession session, String refreshToken) {
        AccessTokenService.IssuedAccessToken accessToken = accessTokenService.issue(user, session.getId());
        TokenResponse response = new TokenResponse(
                accessToken.value(), TokenResponse.BEARER, TokenResponse.EXPIRES_IN_SECONDS, UserResponse.from(user));
        return new AuthenticationResult(response, refreshToken, session.getExpiresAt());
    }

    private void revokeReusedSession(String tokenHash) {
        usedRefreshTokenRepository.findByTokenHash(tokenHash).ifPresent(used ->
                authSessionRepository.findByIdForUpdate(used.getSessionId())
                        .ifPresent(session -> session.revoke(clock.instant())));
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
