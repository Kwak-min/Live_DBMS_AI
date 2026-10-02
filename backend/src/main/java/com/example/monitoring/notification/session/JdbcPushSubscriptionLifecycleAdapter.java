package com.example.monitoring.notification.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
public class JdbcPushSubscriptionLifecycleAdapter implements PushSubscriptionLifecyclePort {
    private final JdbcTemplate jdbcTemplate;

    public JdbcPushSubscriptionLifecycleAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deactivateBySession(UUID sessionId, Instant now) {
        requireNow(now);
        if (sessionId == null) {
            return 0;
        }
        Timestamp at = Timestamp.from(now);
        return jdbcTemplate.update("""
                UPDATE push_subscriptions
                   SET enabled = FALSE, deleted_at = ?, updated_at = ?
                 WHERE sid = ? AND enabled AND deleted_at IS NULL
                """, at, at, sessionId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deactivateByUser(long userId, Instant now) {
        requireNow(now);
        if (userId < 1) {
            return 0;
        }
        Timestamp at = Timestamp.from(now);
        return jdbcTemplate.update("""
                UPDATE push_subscriptions
                   SET enabled = FALSE, deleted_at = ?, updated_at = ?
                 WHERE user_id = ? AND enabled AND deleted_at IS NULL
                """, at, at, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deactivateSessionsEligibleForRetention(Instant expiresBefore, Instant now) {
        requireNow(expiresBefore);
        requireNow(now);
        Timestamp cutoff = Timestamp.from(expiresBefore);
        lockEligibleUsers(cutoff);
        lockEligibleSessions(cutoff);
        Timestamp at = Timestamp.from(now);
        return jdbcTemplate.update("""
                UPDATE push_subscriptions p
                   SET enabled = FALSE, deleted_at = ?, updated_at = ?
                 WHERE p.enabled AND p.deleted_at IS NULL
                   AND EXISTS (
                       SELECT 1 FROM auth_sessions s
                        WHERE s.sid = p.sid
                          AND (s.expires_at < ? OR (s.revoked_at IS NOT NULL AND s.revoked_at < ?))
                   )
                """, at, at, cutoff, cutoff);
    }

    private void lockEligibleUsers(Timestamp cutoff) {
        jdbcTemplate.query("""
                SELECT u.id
                  FROM users u
                 WHERE EXISTS (
                       SELECT 1 FROM auth_sessions s
                        WHERE s.user_id = u.id
                          AND (s.expires_at < ? OR (s.revoked_at IS NOT NULL AND s.revoked_at < ?))
                 )
                 ORDER BY u.id
                 FOR UPDATE
                """, resultSet -> {
                    while (resultSet.next()) {
                        resultSet.getLong(1);
                    }
                    return null;
                }, cutoff, cutoff);
    }

    private void lockEligibleSessions(Timestamp cutoff) {
        jdbcTemplate.query("""
                SELECT sid
                  FROM auth_sessions
                 WHERE expires_at < ? OR (revoked_at IS NOT NULL AND revoked_at < ?)
                 ORDER BY user_id, sid
                 FOR UPDATE
                """, resultSet -> {
                    while (resultSet.next()) {
                        resultSet.getObject(1, UUID.class);
                    }
                    return null;
                }, cutoff, cutoff);
    }

    private void requireNow(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("Notification lifecycle time is required.");
        }
    }
}
