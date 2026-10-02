package com.example.monitoring.notification.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class AuthSessionSecurity {
    private static final String STATE_SQL = """
            SELECT s.revoked_at, s.expires_at, s.auth_version AS session_auth_version,
                   u.enabled, u.auth_version AS user_auth_version
              FROM auth_sessions s
              JOIN users u ON u.id = s.user_id
             WHERE s.sid = ? AND s.user_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AuthSessionSecurity(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isSessionUsable(UUID sessionId, long userId) {
        if (!validIdentity(sessionId, userId)) {
            return false;
        }
        return state(sessionId, userId).map(this::usable).orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockSessionUsable(UUID sessionId, long userId) {
        if (!lockSession(sessionId, userId)) {
            return false;
        }
        return state(sessionId, userId).map(this::usable).orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockSession(UUID sessionId, long userId) {
        if (!validIdentity(sessionId, userId)) {
            return false;
        }
        boolean userLocked = Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT id FROM users WHERE id = ? FOR UPDATE",
                (ResultSetExtractor<Boolean>) ResultSet::next, userId));
        if (!userLocked) {
            return false;
        }
        return Boolean.TRUE.equals(jdbcTemplate.query(
                "SELECT sid FROM auth_sessions WHERE sid = ? AND user_id = ? FOR UPDATE",
                (ResultSetExtractor<Boolean>) ResultSet::next, sessionId, userId));
    }

    @Transactional(readOnly = true)
    public Optional<SessionIdentity> findCurrentRefreshIdentity(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT sid, user_id FROM auth_sessions WHERE current_refresh_hash = ?",
                resultSet -> resultSet.next()
                        ? Optional.of(new SessionIdentity(resultSet.getObject("sid", UUID.class),
                        resultSet.getLong("user_id")))
                        : Optional.empty(),
                tokenHash);
    }

    @Transactional(readOnly = true)
    public Optional<SessionIdentity> findIdentity(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT sid, user_id FROM auth_sessions WHERE sid = ?",
                resultSet -> resultSet.next()
                        ? Optional.of(new SessionIdentity(resultSet.getObject("sid", UUID.class),
                        resultSet.getLong("user_id")))
                        : Optional.empty(),
                sessionId);
    }

    private Optional<SessionState> state(UUID sessionId, long userId) {
        return jdbcTemplate.query(STATE_SQL,
                resultSet -> resultSet.next() ? Optional.of(mapState(resultSet)) : Optional.empty(),
                sessionId, userId);
    }

    private SessionState mapState(ResultSet resultSet) throws SQLException {
        java.sql.Timestamp revoked = resultSet.getTimestamp("revoked_at");
        return new SessionState(revoked == null ? null : revoked.toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getLong("session_auth_version"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("user_auth_version"));
    }

    private boolean usable(SessionState state) {
        Instant now = clock.instant();
        return state.revokedAt() == null && state.expiresAt().isAfter(now) && state.userEnabled()
                && state.sessionAuthVersion() == state.userAuthVersion();
    }

    private boolean validIdentity(UUID sessionId, long userId) {
        return sessionId != null && userId > 0 && userId <= 9_007_199_254_740_991L;
    }

    public record SessionIdentity(UUID sessionId, long userId) { }

    private record SessionState(Instant revokedAt, Instant expiresAt, long sessionAuthVersion,
                                boolean userEnabled, long userAuthVersion) { }
}
