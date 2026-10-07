package com.example.monitoring.auth.service;

import com.example.monitoring.auth.domain.UserAccount;
import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.dto.UpdateUserRoleRequest;
import com.example.monitoring.auth.dto.UpdateUserStatusRequest;
import com.example.monitoring.auth.repository.AuthSessionRepository;
import com.example.monitoring.auth.repository.UserAccountRepository;
import com.example.monitoring.common.persistence.PartBTransactionLocks;
import com.example.monitoring.notification.session.PushSubscriptionLifecyclePort;
import com.example.monitoring.service.AuditEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccountNotificationRevocationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00.123456Z");
    private static final Instant MILLIS_NOW = Instant.parse("2026-10-03T03:00:00.123Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final PasswordHashingService passwords = mock(PasswordHashingService.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final PartBTransactionLocks locks = mock(PartBTransactionLocks.class);
    private final PushSubscriptionLifecyclePort pushSubscriptions = mock(PushSubscriptionLifecyclePort.class);
    private UserAccountService service;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(users, sessions, passwords, audit, locks, pushSubscriptions,
                Clock.fixed(NOW, ZoneOffset.UTC), event -> { });
    }

    @Test
    void roleChangeLocksUserThenRevokesSessionsThenTombstonesPushes() {
        UserAccount user = user(true, UserRole.USER);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        service.updateRole(7L, new UpdateUserRoleRequest(UserRole.ADMIN));

        var order = inOrder(users, sessions, pushSubscriptions);
        order.verify(users).findByIdForUpdate(7L);
        order.verify(sessions).revokeAllByUserId(7L, MILLIS_NOW);
        order.verify(pushSubscriptions).deactivateByUser(7L, MILLIS_NOW);
    }

    @Test
    void disablingUserSynchronouslyTombstonesPushesButNoopChangeDoesNot() {
        UserAccount user = user(true, UserRole.USER);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        service.updateStatus(7L, new UpdateUserStatusRequest(false));
        verify(sessions).revokeAllByUserId(7L, MILLIS_NOW);
        verify(pushSubscriptions).deactivateByUser(7L, MILLIS_NOW);

        UserAccount alreadyDisabled = user(false, UserRole.USER);
        when(users.findByIdForUpdate(8L)).thenReturn(Optional.of(alreadyDisabled));
        service.updateStatus(8L, new UpdateUserStatusRequest(false));
        verify(sessions, never()).revokeAllByUserId(8L, MILLIS_NOW);
        verify(pushSubscriptions, never()).deactivateByUser(8L, MILLIS_NOW);
    }

    private UserAccount user(boolean enabled, UserRole role) {
        return UserAccount.builder().id(enabled ? 7L : 8L).email("user@example.test").displayName("user")
                .passwordHash("hash").role(role).enabled(enabled).authVersion(1L).build();
    }
}
