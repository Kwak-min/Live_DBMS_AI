package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.AuthSessionsRevokedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StompSessionRevocationListenerTest {

    private final StompSessionRegistry registry = mock(StompSessionRegistry.class);
    private final StompSessionRevocationListener listener = new StompSessionRevocationListener(registry);

    @Test
    void sessionRevocationClosesThatAuthenticationSession() {
        UUID sid = UUID.randomUUID();

        listener.onRevoked(AuthSessionsRevokedEvent.session(sid));

        verify(registry).closeAuthenticationSession(sid);
        verify(registry, never()).closeUserSessions(anyLong());
    }

    @Test
    void userWideRevocationClosesEverySocketOfThatUser() {
        listener.onRevoked(AuthSessionsRevokedEvent.user(42L));

        verify(registry).closeUserSessions(42L);
    }
}
