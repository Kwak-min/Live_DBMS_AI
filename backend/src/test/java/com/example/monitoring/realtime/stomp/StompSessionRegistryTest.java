package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.StompAccessTokenAuthenticator;
import com.example.monitoring.auth.service.StompPrincipal;
import com.example.monitoring.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompSessionRegistryTest {
    @Test
    void atomicallyAdmitsAtMostFiveSocketsForAnAccount() throws Exception {
        StompAccessTokenAuthenticator authenticator = mock(StompAccessTokenAuthenticator.class);
        StompPrincipal principal = StompTestSupport.principal(41L);
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(principal);
        StompSessionRegistry registry = StompTestSupport.registry(authenticator, StompTestSupport.scheduler());
        List<String> sessionIds = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String sessionId = "socket-" + index;
            sessionIds.add(sessionId);
            registry.opened(StompTestSupport.socket(sessionId));
        }
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = sessionIds.stream().map(sessionId -> executor.submit(() -> {
            start.await();
            try {
                registry.connected(sessionId, "Bearer token");
                return true;
            } catch (StompTransportException failure) {
                assertThat(failure.failure().code()).isEqualTo("FORBIDDEN");
                return false;
            }
        })).toList();

        start.countDown();
        long admitted = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                admitted++;
            }
        }
        executor.shutdownNow();

        assertThat(admitted).isEqualTo(5);
        assertThat(registry.authenticatedSessionCount()).isEqualTo(5);
        sessionIds.forEach(registry::disconnected);
        assertThat(registry.authenticatedSessionCount()).isZero();
    }

    @Test
    void revokedSessionFailsClosedBeforeOutboundDelivery() throws Exception {
        StompAccessTokenAuthenticator authenticator = mock(StompAccessTokenAuthenticator.class);
        StompPrincipal principal = StompTestSupport.principal(9L);
        when(authenticator.authenticateAuthorization("Bearer token"))
                .thenReturn(principal)
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "secret detail"));
        TaskScheduler scheduler = StompTestSupport.scheduler();
        StompSessionRegistry registry = StompTestSupport.registry(authenticator, scheduler);
        WebSocketSession rawSocket = StompTestSupport.rawSocket("socket-a");
        StompSerializedWebSocketSession socket = StompTestSupport.serializedSocket(rawSocket);
        registry.opened(socket);
        registry.connected("socket-a", "Bearer token");

        assertThat(registry.revalidateForDelivery("socket-a")).isFalse();

        org.mockito.ArgumentCaptor<TextMessage> errorFrame = forClass(TextMessage.class);
        verify(rawSocket).sendMessage(errorFrame.capture());
        verify(rawSocket).close(CloseStatus.PROTOCOL_ERROR);
        assertThat(errorFrame.getValue().getPayload())
                .contains("ERROR\n", "\"code\":\"SESSION_REVOKED\"")
                .doesNotContain("secret detail");
        assertThat(errorFrame.getValue().getPayload().charAt(
                errorFrame.getValue().getPayloadLength() - 1)).isEqualTo('\0');
        assertThat(registry.authenticatedSessionCount()).isZero();
    }

    @Test
    void unauthenticatedSocketTimesOutAndReleasesItsRegistryEntry() throws Exception {
        StompSessionRegistry registry = StompTestSupport.registry(
                mock(StompAccessTokenAuthenticator.class), StompTestSupport.scheduler());
        WebSocketSession rawSocket = StompTestSupport.rawSocket("pending");
        StompSerializedWebSocketSession socket = StompTestSupport.serializedSocket(rawSocket);
        registry.opened(socket);

        registry.connectTimedOut("pending");

        verify(rawSocket).sendMessage(any(TextMessage.class));
        verify(rawSocket).close(CloseStatus.PROTOCOL_ERROR);
        assertThat(registry.pendingSessionCount()).isZero();
    }

    @Test
    void privateCredentialSnapshotNeverRendersTheBearer() throws Exception {
        StompAccessTokenAuthenticator authenticator = mock(StompAccessTokenAuthenticator.class);
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(5L));
        StompSessionRegistry registry = StompTestSupport.registry(authenticator, StompTestSupport.scheduler());
        registry.opened(StompTestSupport.socket("private-credentials"));
        registry.connected("private-credentials", "Bearer do-not-render");
        Method credentials = StompSessionRegistry.class.getDeclaredMethod("credentials", String.class);
        credentials.setAccessible(true);

        Object snapshot = credentials.invoke(registry, "private-credentials");

        assertThat(snapshot.toString()).doesNotContain("do-not-render");
    }

    @Test
    void capsAndDeduplicatesSubscriptionsPerSocket() {
        StompAccessTokenAuthenticator authenticator = mock(StompAccessTokenAuthenticator.class);
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(3L));
        StompSessionRegistry registry = StompTestSupport.registry(authenticator, StompTestSupport.scheduler());
        registry.opened(StompTestSupport.socket("subscriptions"));
        registry.connected("subscriptions", "Bearer token");
        for (int index = 0; index < StompSessionRegistry.MAX_SUBSCRIPTIONS_PER_SOCKET; index++) {
            registry.addSubscription("subscriptions", "sub-" + index,
                    new StompDestination(StompDestination.Type.METRICS, 1L));
        }

        assertThatThrownBy(() -> registry.addSubscription("subscriptions", "overflow",
                new StompDestination(StompDestination.Type.METRICS, 1L)))
                .isInstanceOf(StompTransportException.class);
        assertThat(registry.subscriptionCount("subscriptions"))
                .isEqualTo(StompSessionRegistry.MAX_SUBSCRIPTIONS_PER_SOCKET);
    }
}
