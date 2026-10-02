package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.domain.UserRole;
import com.example.monitoring.auth.service.StompAccessTokenAuthenticator;
import com.example.monitoring.auth.service.StompPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class StompTestSupport {
    static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private StompTestSupport() {
    }

    static StompPrincipal principal(long userId) {
        return new StompPrincipal(userId, UserRole.USER, UUID.randomUUID(), NOW.plusSeconds(300));
    }

    static TaskScheduler scheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        doReturn(task).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        return scheduler;
    }

    static StompSerializedWebSocketSession socket(String id) {
        return serializedSocket(rawSocket(id));
    }

    static WebSocketSession rawSocket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    static StompSerializedWebSocketSession serializedSocket(WebSocketSession socket) {
        return new StompSerializedWebSocketSession(socket);
    }

    static StompSessionRegistry registry(StompAccessTokenAuthenticator authenticator, TaskScheduler scheduler) {
        return new StompSessionRegistry(authenticator, scheduler, new ObjectMapper(), CLOCK);
    }
}
