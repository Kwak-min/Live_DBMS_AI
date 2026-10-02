package com.example.monitoring.realtime.integration;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class Stage3RawWebSocketClient {
    private static final int FRAME_LIMIT = 64 * 1024;
    private final StandardWebSocketClient client = new StandardWebSocketClient();

    Connection open(int port, String origin) throws Exception {
        Probe handler = new Probe();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        WebSocketSession session = client.execute(
                handler,
                headers,
                URI.create("ws://127.0.0.1:" + port + "/ws"))
                .get(10, TimeUnit.SECONDS);
        return new Connection(session, handler);
    }

    Connection beginConnect(int port, String origin, String token) throws Exception {
        Connection connection = open(port, origin);
        String authorization = token == null ? "" : "Authorization:Bearer " + token + "\n";
        connection.send(("CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n"
                + authorization + "\n\0").getBytes(StandardCharsets.UTF_8));
        return connection;
    }

    Connection connect(int port, String origin, String token) throws Exception {
        Connection connection = beginConnect(port, origin, token);
        String connected = connection.message(Duration.ofSeconds(10));
        if (connected == null || !connected.startsWith("CONNECTED")) {
            throw new IllegalStateException("Raw client did not receive CONNECTED");
        }
        return connection;
    }

    static byte[] forbiddenSendFrame(int totalBytes) {
        if (totalBytes < 128) {
            throw new IllegalArgumentException("Frame probe must leave room for STOMP headers");
        }
        int bodyBytes = totalBytes;
        byte[] prefix;
        while (true) {
            prefix = ("SEND\ndestination:/forbidden\ncontent-length:" + bodyBytes + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            int adjustedBodyBytes = totalBytes - prefix.length - 1;
            if (bodyBytes == adjustedBodyBytes) {
                break;
            }
            bodyBytes = adjustedBodyBytes;
        }
        byte[] frame = new byte[totalBytes];
        System.arraycopy(prefix, 0, frame, 0, prefix.length);
        Arrays.fill(frame, prefix.length, prefix.length + bodyBytes, (byte) 'x');
        frame[frame.length - 1] = 0;
        return frame;
    }

    static int limit() {
        return FRAME_LIMIT;
    }

    static final class Connection implements AutoCloseable {
        private final WebSocketSession session;
        private final Probe probe;

        private Connection(WebSocketSession session, Probe probe) {
            this.session = session;
            this.probe = probe;
        }

        void send(byte[] payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        void subscribe(String id, String destination) throws Exception {
            send(("SUBSCRIBE\nid:" + id + "\nack:auto\ndestination:" + destination
                    + "\n\n\0").getBytes(StandardCharsets.UTF_8));
        }

        String message(Duration timeout) throws InterruptedException {
            ObservedMessage observed = observedMessage(timeout);
            return observed == null ? null : observed.payload();
        }

        ObservedMessage observedMessage(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            long remaining = timeout.toNanos();
            while (remaining > 0) {
                ObservedMessage observed = probe.messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (observed == null || !observed.payload().isBlank()) {
                    return observed;
                }
                remaining = deadline - System.nanoTime();
            }
            return null;
        }

        CloseStatus closed(Duration timeout) throws Exception {
            return observedClose(timeout).status();
        }

        ObservedClose observedClose(Duration timeout) throws Exception {
            return probe.closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    record ObservedMessage(String payload, long observedAtNanos, String transportType) { }

    record ObservedClose(CloseStatus status, long observedAtNanos) { }

    private static final class Probe extends AbstractWebSocketHandler {
        private final BlockingQueue<ObservedMessage> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<ObservedClose> closed = new CompletableFuture<>();

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            if (message instanceof TextMessage text) {
                messages.add(new ObservedMessage(text.getPayload(), System.nanoTime(), "text"));
            } else if (message instanceof BinaryMessage binary) {
                ByteBuffer payload = binary.getPayload().asReadOnlyBuffer();
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                messages.add(new ObservedMessage(
                        new String(bytes, StandardCharsets.UTF_8), System.nanoTime(), "binary"));
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            closed.completeExceptionally(exception);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(new ObservedClose(status, System.nanoTime()));
        }
    }
}
