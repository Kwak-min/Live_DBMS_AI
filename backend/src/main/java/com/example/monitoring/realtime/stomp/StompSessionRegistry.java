package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.StompAccessTokenAuthenticator;
import com.example.monitoring.auth.service.StompPrincipal;
import com.example.monitoring.common.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class StompSessionRegistry {
    static final int MAX_SOCKETS_PER_ACCOUNT = 5;
    static final int MAX_SUBSCRIPTIONS_PER_SOCKET = 61;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REVALIDATION_INTERVAL = Duration.ofSeconds(10);

    private final StompAccessTokenAuthenticator authenticator;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, SessionState> sessions = new HashMap<>();
    private final Map<Long, Integer> accountSocketCounts = new HashMap<>();
    private volatile ScheduledFuture<?> revalidationTask;

    @Autowired
    public StompSessionRegistry(StompAccessTokenAuthenticator authenticator,
                                @Qualifier("stompTaskScheduler") TaskScheduler scheduler,
                                ObjectMapper objectMapper) {
        this(authenticator, scheduler, objectMapper, Clock.systemUTC());
    }

    StompSessionRegistry(StompAccessTokenAuthenticator authenticator, TaskScheduler scheduler,
                         ObjectMapper objectMapper, Clock clock) {
        this.authenticator = authenticator;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        revalidationTask = scheduler.scheduleAtFixedRate(this::revalidateAll, REVALIDATION_INTERVAL);
    }

    void opened(StompSerializedWebSocketSession socket) {
        SessionState state = new SessionState(socket);
        lock.lock();
        try {
            if (sessions.putIfAbsent(socket.getId(), state) != null) {
                throw new IllegalStateException("Duplicate WebSocket session id");
            }
            try {
                state.connectTimeout = scheduler.schedule(
                        () -> connectTimedOut(socket.getId()), clock.instant().plus(CONNECT_TIMEOUT));
            } catch (RuntimeException schedulingFailure) {
                sessions.remove(socket.getId());
                throw schedulingFailure;
            }
        } finally {
            lock.unlock();
        }
    }

    StompPrincipal connected(String sessionId, String authorization) {
        StompPrincipal authenticated = authenticate(authorization);
        if (!authenticated.expiresAt().isAfter(clock.instant())) {
            throw new StompTransportException(StompFailure.of("ACCESS_TOKEN_EXPIRED"));
        }
        lock.lock();
        try {
            SessionState state = requireState(sessionId);
            if (state.principal != null) {
                throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
            }
            int socketCount = accountSocketCounts.getOrDefault(authenticated.userId(), 0);
            if (socketCount >= MAX_SOCKETS_PER_ACCOUNT) {
                throw new StompTransportException(StompFailure.of("FORBIDDEN"));
            }
            cancel(state.connectTimeout);
            state.connectTimeout = null;
            state.authorization = authorization;
            state.principal = authenticated;
            accountSocketCounts.put(authenticated.userId(), socketCount + 1);
            try {
                state.expiry = scheduler.schedule(() -> tokenExpired(sessionId), authenticated.expiresAt());
            } catch (RuntimeException schedulingFailure) {
                state.authorization = null;
                state.principal = null;
                decrementAccount(authenticated.userId());
                throw new StompTransportException(StompFailure.of("SESSION_REVOKED"));
            }
            return authenticated;
        } finally {
            lock.unlock();
        }
    }

    StompPrincipal requireCurrent(String sessionId) {
        SessionCredentials credentials = credentials(sessionId);
        StompPrincipal current = authenticate(credentials.authorization());
        if (!sameIdentity(credentials.principal(), current) || !current.expiresAt().isAfter(clock.instant())) {
            throw new StompTransportException(StompFailure.of("SESSION_REVOKED"));
        }
        return current;
    }

    boolean revalidateForDelivery(String sessionId) {
        try {
            requireCurrent(sessionId);
            return true;
        } catch (RuntimeException failure) {
            terminate(sessionId, authenticationFailure(failure));
            return false;
        }
    }

    void addSubscription(String sessionId, String subscriptionId, StompDestination destination) {
        lock.lock();
        try {
            SessionState state = requireAuthenticatedState(sessionId);
            if (state.subscriptions.size() >= MAX_SUBSCRIPTIONS_PER_SOCKET
                    || state.subscriptions.containsKey(subscriptionId)) {
                throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
            }
            state.subscriptions.put(subscriptionId, destination);
        } finally {
            lock.unlock();
        }
    }

    void removeSubscription(String sessionId, String subscriptionId) {
        lock.lock();
        try {
            SessionState state = requireAuthenticatedState(sessionId);
            if (state.subscriptions.remove(subscriptionId) == null) {
                throw new StompTransportException(StompFailure.of("VALIDATION_ERROR"));
            }
        } finally {
            lock.unlock();
        }
    }

    boolean hasErrorsSubscription(String sessionId) {
        lock.lock();
        try {
            SessionState state = sessions.get(sessionId);
            return state != null && state.subscriptions.values().stream()
                    .anyMatch(destination -> destination.type() == StompDestination.Type.ERRORS);
        } finally {
            lock.unlock();
        }
    }

    void disconnected(String sessionId) {
        SessionState removed = removeSession(sessionId);
        cancelLifecycleTasks(removed);
    }

    void closeWithError(String sessionId, StompFailure failure) {
        terminate(sessionId, failure);
    }

    public void closeAuthenticationSession(UUID authenticationSessionId) {
        Set<String> matched = new HashSet<>();
        lock.lock();
        try {
            sessions.forEach((sessionId, state) -> {
                if (state.principal != null && state.principal.sessionId().equals(authenticationSessionId)) {
                    matched.add(sessionId);
                }
            });
        } finally {
            lock.unlock();
        }
        matched.forEach(sessionId -> terminate(sessionId, StompFailure.of("SESSION_REVOKED")));
    }

    public int authenticatedSessionCount() {
        lock.lock();
        try {
            return (int) sessions.values().stream().filter(state -> state.principal != null).count();
        } finally {
            lock.unlock();
        }
    }

    public int pendingSessionCount() {
        lock.lock();
        try {
            return (int) sessions.values().stream().filter(state -> state.principal == null).count();
        } finally {
            lock.unlock();
        }
    }

    public int subscriptionCount(String sessionId) {
        lock.lock();
        try {
            SessionState state = sessions.get(sessionId);
            return state == null ? 0 : state.subscriptions.size();
        } finally {
            lock.unlock();
        }
    }

    void connectTimedOut(String sessionId) {
        lock.lock();
        try {
            SessionState state = sessions.get(sessionId);
            if (state == null || state.principal != null) {
                return;
            }
        } finally {
            lock.unlock();
        }
        terminate(sessionId, StompFailure.of("AUTH_REQUIRED"));
    }

    private void tokenExpired(String sessionId) {
        terminate(sessionId, StompFailure.of("ACCESS_TOKEN_EXPIRED"));
    }

    private void revalidateAll() {
        ArrayList<String> authenticatedSessionIds = new ArrayList<>();
        lock.lock();
        try {
            sessions.forEach((sessionId, state) -> {
                if (state.principal != null) {
                    authenticatedSessionIds.add(sessionId);
                }
            });
        } finally {
            lock.unlock();
        }
        authenticatedSessionIds.forEach(this::revalidateForDelivery);
    }

    private StompPrincipal authenticate(String authorization) {
        try {
            return authenticator.authenticateAuthorization(authorization);
        } catch (ApiException failure) {
            throw new StompTransportException(authenticationFailure(failure));
        } catch (RuntimeException dependencyFailure) {
            throw new StompTransportException(StompFailure.of("SESSION_REVOKED"));
        }
    }

    private StompFailure authenticationFailure(Throwable failure) {
        StompFailure mapped = StompFailure.from(failure);
        return switch (mapped.code()) {
            case "AUTH_REQUIRED", "ACCESS_TOKEN_EXPIRED", "INVALID_TOKEN", "SESSION_REVOKED" -> mapped;
            default -> StompFailure.of("SESSION_REVOKED");
        };
    }

    private SessionCredentials credentials(String sessionId) {
        lock.lock();
        try {
            SessionState state = requireAuthenticatedState(sessionId);
            return new SessionCredentials(state.authorization, state.principal);
        } finally {
            lock.unlock();
        }
    }

    private SessionState requireState(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new StompTransportException(StompFailure.of("AUTH_REQUIRED"));
        }
        return state;
    }

    private SessionState requireAuthenticatedState(String sessionId) {
        SessionState state = requireState(sessionId);
        if (state.principal == null || state.authorization == null) {
            throw new StompTransportException(StompFailure.of("AUTH_REQUIRED"));
        }
        return state;
    }

    private boolean sameIdentity(StompPrincipal expected, StompPrincipal actual) {
        return expected.userId().equals(actual.userId())
                && expected.role() == actual.role()
                && expected.sessionId().equals(actual.sessionId())
                && expected.expiresAt().equals(actual.expiresAt());
    }

    private void terminate(String sessionId, StompFailure failure) {
        SessionState removed = removeSession(sessionId);
        if (removed == null) {
            return;
        }
        cancelLifecycleTasks(removed);
        sendErrorAndClose(removed.socket, failure);
    }

    private SessionState removeSession(String sessionId) {
        lock.lock();
        try {
            SessionState removed = sessions.remove(sessionId);
            if (removed == null) {
                return null;
            }
            removed.socket.beginClosing();
            if (removed.principal != null) {
                decrementAccount(removed.principal.userId());
            }
            removed.authorization = null;
            removed.principal = null;
            removed.subscriptions.clear();
            return removed;
        } finally {
            lock.unlock();
        }
    }

    private void cancelLifecycleTasks(SessionState state) {
        if (state == null) {
            return;
        }
        cancel(state.connectTimeout);
        cancel(state.expiry);
    }

    private void sendErrorAndClose(StompSerializedWebSocketSession socket, StompFailure failure) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(new ErrorBody(
                    failure.code(), failure.message(), failure.requestId()));
            byte[] prefix = ("ERROR\ncontent-type:application/json\ncontent-length:" + json.length + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[prefix.length + json.length + 1];
            System.arraycopy(prefix, 0, frame, 0, prefix.length);
            System.arraycopy(json, 0, frame, prefix.length, json.length);
            socket.sendErrorAndClose(new TextMessage(frame), CloseStatus.PROTOCOL_ERROR);
        } catch (JsonProcessingException ignored) {
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close(CloseStatus.PROTOCOL_ERROR);
            } catch (IOException ignored) {
            }
        }
    }

    private void decrementAccount(Long userId) {
        int next = accountSocketCounts.getOrDefault(userId, 1) - 1;
        if (next <= 0) {
            accountSocketCounts.remove(userId);
        } else {
            accountSocketCounts.put(userId, next);
        }
    }

    private void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    @PreDestroy
    void stop() {
        cancel(revalidationTask);
        ArrayList<String> sessionIds;
        lock.lock();
        try {
            sessionIds = new ArrayList<>(sessions.keySet());
        } finally {
            lock.unlock();
        }
        sessionIds.forEach(this::disconnected);
    }

    private static final class SessionCredentials {
        private final String authorization;
        private final StompPrincipal principal;

        private SessionCredentials(String authorization, StompPrincipal principal) {
            this.authorization = authorization;
            this.principal = principal;
        }

        private String authorization() {
            return authorization;
        }

        private StompPrincipal principal() {
            return principal;
        }
    }

    private record ErrorBody(String code, String message, String requestId) { }

    private static final class SessionState {
        private final StompSerializedWebSocketSession socket;
        private final Map<String, StompDestination> subscriptions = new HashMap<>();
        private String authorization;
        private StompPrincipal principal;
        private ScheduledFuture<?> connectTimeout;
        private ScheduledFuture<?> expiry;

        private SessionState(StompSerializedWebSocketSession socket) {
            this.socket = socket;
        }
    }
}
