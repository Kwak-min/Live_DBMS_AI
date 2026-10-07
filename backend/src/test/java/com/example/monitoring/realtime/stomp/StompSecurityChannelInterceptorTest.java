package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.StompAccessTokenAuthenticator;
import com.example.monitoring.auth.service.StompPrincipal;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.common.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompSecurityChannelInterceptorTest {
    private StompAccessTokenAuthenticator authenticator;
    private TargetProvider targetProvider;
    private CapturingErrorSender errorSender;
    private StompSessionRegistry sessions;
    private StompSecurityChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authenticator = mock(StompAccessTokenAuthenticator.class);
        targetProvider = mock(TargetProvider.class);
        errorSender = new CapturingErrorSender();
        sessions = StompTestSupport.registry(authenticator, StompTestSupport.scheduler());
        interceptor = new StompSecurityChannelInterceptor(sessions, targetProvider, errorSender);
    }

    @Test
    void connectsWithOnlyTheNativeBearerAndSubscribesToDisabledLiveTarget() {
        StompPrincipal principal = StompTestSupport.principal(17L);
        when(authenticator.authenticateAuthorization("Bearer access")).thenReturn(principal);
        when(targetProvider.getMetadata(12L)).thenReturn(Optional.of(
                new TargetMetadata(12L, 4L, "db", "host", 3306, "schema", false)));
        sessions.opened(StompTestSupport.socket("session-a"));

        Message<?> connected = interceptor.preSend(connect("session-a", "Bearer access"), channel());
        Message<?> subscribed = interceptor.preSend(
                subscribe("session-a", "target", "/topic/databases/12/metrics"), channel());

        StompHeaderAccessor connectedHeaders = MessageHeaderAccessor.getAccessor(
                connected, StompHeaderAccessor.class);
        assertThat(connectedHeaders).isNotNull();
        assertThat(connectedHeaders.getUser()).isEqualTo(principal);
        assertThat(connectedHeaders.getNativeHeader("Authorization")).isNull();
        assertThat(subscribed).isNotNull();
        assertThat(sessions.subscriptionCount("session-a")).isEqualTo(1);
        verify(targetProvider).getMetadata(12L);
    }

    @Test
    void rejectsLoginPasscodeAndMissingAuthorizationAtConnect() {
        sessions.opened(StompTestSupport.socket("session-a"));
        Message<?> login = connect("session-a", "Bearer access", "login", "user");
        assertThat(interceptor.preSend(login, channel())).isNull();

        sessions.opened(StompTestSupport.socket("session-b"));
        assertThat(interceptor.preSend(connect("session-b", null), channel())).isNull();
        assertThat(sessions.pendingSessionCount()).isZero();
    }

    @Test
    void removesBearerFromTheFrameBeforeAuthenticationCanFail() {
        when(authenticator.authenticateAuthorization("Bearer secret"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "sensitive"));
        sessions.opened(StompTestSupport.socket("session-a"));
        Message<?> frame = connect("session-a", "Bearer secret");

        assertThat(interceptor.preSend(frame, channel())).isNull();

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(frame, StompHeaderAccessor.class);
        assertThat(accessor).isNotNull();
        assertThat(accessor.getNativeHeader("Authorization")).isNull();
        assertThat(sessions.pendingSessionCount()).isZero();
    }

    @Test
    void sendsImmediateInvalidTokenErrorWhenOrderedDeliveryConsumesTheInterceptorFailure() throws Exception {
        when(authenticator.authenticateAuthorization("Bearer secret"))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "sensitive"));
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> connectDeadline = mock(ScheduledFuture.class);
        doReturn(connectDeadline).when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        StompSessionRegistry orderedSessions = new StompSessionRegistry(
                authenticator, taskScheduler, new ObjectMapper(), StompTestSupport.CLOCK);
        StompSecurityChannelInterceptor orderedInterceptor = new StompSecurityChannelInterceptor(
                orderedSessions, targetProvider, errorSender);
        WebSocketSession rawSocket = StompTestSupport.rawSocket("session-a");
        orderedSessions.opened(StompTestSupport.serializedSocket(rawSocket));
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(Runnable::run);
        inbound.addInterceptor(orderedInterceptor);
        OrderedMessageChannelDecorator ordered = new OrderedMessageChannelDecorator(
                inbound, LogFactory.getLog(StompSecurityChannelInterceptorTest.class));

        assertThat(ordered.send(connect("session-a", "Bearer secret"))).isTrue();

        org.mockito.ArgumentCaptor<TextMessage> sent =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(rawSocket).sendMessage(sent.capture());
        String frame = new String(sent.getValue().asBytes(), StandardCharsets.UTF_8);
        assertThat(frame).startsWith("ERROR\n")
                .contains("\"code\":\"INVALID_TOKEN\"")
                .doesNotContain("sensitive", "secret");
        verify(rawSocket).close(eq(CloseStatus.PROTOCOL_ERROR));
        verify(connectDeadline).cancel(false);
        assertThat(orderedSessions.pendingSessionCount()).isZero();
    }

    @Test
    void sendsInvalidSubscriptionOnlyToTheCurrentSessionsErrorsQueue() {
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(2L));
        sessions.opened(StompTestSupport.socket("session-a"));
        interceptor.preSend(connect("session-a", "Bearer token"), channel());
        interceptor.preSend(subscribe("session-a", "errors", "/user/queue/errors"), channel());

        Message<?> rejected = interceptor.preSend(
                subscribe("session-a", "bad", "/topic/databases/*/metrics"), channel());

        assertThat(rejected).isNull();
        assertThat(errorSender.sessionId).isEqualTo("session-a");
        assertThat(errorSender.subscriptionId).isEqualTo("bad");
        assertThat(errorSender.failure.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(sessions.subscriptionCount("session-a")).isEqualTo(1);
    }

    @Test
    void doesNotEchoAnInvalidSubscriptionIdInTheErrorEnvelope() {
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(2L));
        sessions.opened(StompTestSupport.socket("session-a"));
        interceptor.preSend(connect("session-a", "Bearer token"), channel());
        interceptor.preSend(subscribe("session-a", "errors", "/user/queue/errors"), channel());

        Message<?> rejected = interceptor.preSend(
                subscribe("session-a", "x".repeat(129), "/topic/databases/12/metrics"), channel());

        assertThat(rejected).isNull();
        assertThat(errorSender.sessionId).isEqualTo("session-a");
        assertThat(errorSender.subscriptionId).isNull();
        assertThat(errorSender.failure.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void closesThroughErrorPathWhenErrorsQueueWasNotRegistered() {
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(2L));
        sessions.opened(StompTestSupport.socket("session-a"));
        interceptor.preSend(connect("session-a", "Bearer token"), channel());

        assertThat(interceptor.preSend(
                subscribe("session-a", "missing", "/topic/databases/44/status"), channel())).isNull();
        assertThat(sessions.authenticatedSessionCount()).isZero();
    }

    @Test
    void rejectsClientSendAndNonAutoAcknowledgement() {
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(2L));
        sessions.opened(StompTestSupport.socket("session-a"));
        interceptor.preSend(connect("session-a", "Bearer token"), channel());

        assertThat(interceptor.preSend(frame(StompCommand.SEND, "session-a"), channel())).isNull();

        sessions.opened(StompTestSupport.socket("session-b"));
        interceptor.preSend(connect("session-b", "Bearer token"), channel());
        Message<?> manualAck = subscribe("session-b", "manual", "/user/queue/errors");
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(manualAck, StompHeaderAccessor.class);
        accessor.setNativeHeader("ack", "client");
        assertThat(interceptor.preSend(manualAck, channel())).isNull();
        assertThat(sessions.authenticatedSessionCount()).isZero();
    }

    @Test
    void acceptsSendToAppDestinationWhenAuthenticated() {
        when(authenticator.authenticateAuthorization(anyString())).thenReturn(StompTestSupport.principal(2L));
        sessions.opened(StompTestSupport.socket("session-app"));
        interceptor.preSend(connect("session-app", "Bearer token"), channel());

        StompHeaderAccessor sendAccessor = StompHeaderAccessor.create(StompCommand.SEND);
        sendAccessor.setSessionId("session-app");
        sendAccessor.setDestination("/app/ping");
        Message<?> sendMsg = mutableMessage(sendAccessor);

        assertThat(interceptor.preSend(sendMsg, channel())).isNotNull();
    }

    @Test
    void authenticatesViaTokenHeaderAndSessionAttributes() {
        when(authenticator.authenticateAuthorization("Bearer token-header")).thenReturn(StompTestSupport.principal(5L));
        sessions.opened(StompTestSupport.socket("session-token"));

        StompHeaderAccessor tokenAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        tokenAccessor.setSessionId("session-token");
        tokenAccessor.setNativeHeader("accept-version", "1.2");
        tokenAccessor.setNativeHeader("heart-beat", "10000,10000");
        tokenAccessor.setNativeHeader("token", "token-header");
        Message<?> connectToken = mutableMessage(tokenAccessor);

        assertThat(interceptor.preSend(connectToken, channel())).isNotNull();
        assertThat(tokenAccessor.getNativeHeader("token")).isNull();

        when(authenticator.authenticateAuthorization("Bearer query-param")).thenReturn(StompTestSupport.principal(6L));
        sessions.opened(StompTestSupport.socket("session-query"));

        StompHeaderAccessor queryAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        queryAccessor.setSessionId("session-query");
        queryAccessor.setNativeHeader("accept-version", "1.2");
        queryAccessor.setNativeHeader("heart-beat", "10000,10000");
        queryAccessor.setSessionAttributes(java.util.Map.of("token", "query-param"));
        Message<?> connectQuery = mutableMessage(queryAccessor);

        assertThat(interceptor.preSend(connectQuery, channel())).isNotNull();
    }

    private Message<?> connect(String sessionId, String authorization, String... extraHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setNativeHeader("accept-version", "1.2");
        accessor.setNativeHeader("heart-beat", "10000,10000");
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        if (extraHeader.length == 2) {
            accessor.setNativeHeader(extraHeader[0], extraHeader[1]);
        }
        return mutableMessage(accessor);
    }

    private Message<?> subscribe(String sessionId, String subscriptionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination(destination);
        accessor.setNativeHeader("ack", "auto");
        return mutableMessage(accessor);
    }

    private Message<?> frame(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        return mutableMessage(accessor);
    }

    private Message<?> mutableMessage(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private MessageChannel channel() {
        return mock(MessageChannel.class);
    }

    private static final class CapturingErrorSender implements StompSubscriptionErrorSender {
        private String sessionId;
        private String subscriptionId;
        private StompFailure failure;

        @Override
        public void send(String sessionId, Long databaseConfigId,
                         String subscriptionId, StompFailure failure) {
            this.sessionId = sessionId;
            this.subscriptionId = subscriptionId;
            this.failure = failure;
        }
    }
}
