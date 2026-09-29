package com.example.monitoring.realtime.integration;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class Stage3StompClient implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private final ThreadPoolTaskScheduler scheduler;
    private final WebSocketStompClient client;

    Stage3StompClient() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("stage3-stomp-client-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setTaskScheduler(scheduler);
        client.setDefaultHeartbeat(new long[]{10_000L, 10_000L});
        client.setInboundMessageSizeLimit(128 * 1024);
    }

    ConnectAttempt begin(int port, String origin, String token) {
        return begin(URI.create("ws://127.0.0.1:" + port + "/ws"), origin, token, headers -> { });
    }

    ConnectAttempt begin(URI uri, String origin, String token, HeaderCustomizer customizer) {
        return begin(uri, origin, token, headers -> { }, customizer);
    }

    ConnectAttempt begin(URI uri, String origin, String token,
                         HandshakeCustomizer handshakeCustomizer, HeaderCustomizer customizer) {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        handshakeCustomizer.customize(handshake);
        StompHeaders connect = new StompHeaders();
        connect.setAcceptVersion("1.2");
        connect.setHeartbeat(new long[]{10_000L, 10_000L});
        if (token != null) {
            connect.add("Authorization", "Bearer " + token);
        }
        customizer.customize(connect);
        SessionEvents events = new SessionEvents();
        CompletableFuture<StompSession> future = client.connectAsync(uri, handshake, connect, events);
        return new ConnectAttempt(future, events);
    }

    @Override
    public void close() {
        client.stop();
        scheduler.shutdown();
    }

    @FunctionalInterface
    interface HandshakeCustomizer {
        void customize(WebSocketHttpHeaders headers);
    }

    @FunctionalInterface
    interface HeaderCustomizer {
        void customize(StompHeaders headers);
    }

    static final class ConnectAttempt {
        private final CompletableFuture<StompSession> future;
        private final SessionEvents events;

        private ConnectAttempt(CompletableFuture<StompSession> future, SessionEvents events) {
            this.future = future;
            this.events = events;
        }

        Connection connected() throws Exception {
            return new Connection(future.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), events);
        }

        byte[] protocolError(Duration timeout) throws Exception {
            return events.protocolErrors.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Throwable terminal(Duration timeout) throws Exception {
            return events.terminal.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean failed() {
            return future.isCompletedExceptionally();
        }
    }

    static final class Connection implements AutoCloseable {
        private final StompSession session;
        private final SessionEvents events;

        private Connection(StompSession session, SessionEvents events) {
            this.session = session;
            this.events = events;
        }

        FrameQueue subscribe(String id, String destination) {
            StompHeaders headers = new StompHeaders();
            headers.setId(id);
            headers.setAck("auto");
            headers.setDestination(destination);
            FrameQueue frames = new FrameQueue();
            session.subscribe(headers, frames);
            return frames;
        }

        void send(String destination, byte[] payload) {
            session.send(destination, payload);
        }

        boolean connected() {
            return session.isConnected();
        }

        byte[] protocolError(Duration timeout) throws InterruptedException {
            return events.protocolErrors.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Throwable terminal(Duration timeout) throws Exception {
            return events.terminal.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    static final class FrameQueue implements StompFrameHandler {
        private final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.add(((byte[]) Objects.requireNonNull(payload)).clone());
        }

        byte[] poll(Duration timeout) throws InterruptedException {
            return frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        int size() {
            return frames.size();
        }
    }

    private static final class SessionEvents extends StompSessionHandlerAdapter {
        private final BlockingQueue<byte[]> protocolErrors = new LinkedBlockingQueue<>();
        private final CompletableFuture<Throwable> terminal = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            protocolErrors.add(((byte[]) Objects.requireNonNull(payload)).clone());
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
            if (command == StompCommand.ERROR && payload != null && payload.length > 0) {
                protocolErrors.add(payload.clone());
            }
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            terminal.complete(exception);
        }
    }
}
