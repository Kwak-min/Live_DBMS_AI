package com.example.monitoring.realtime.stomp;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StompSerializedWebSocketSessionTest {
    @Test
    void serializesErrorAfterAnActiveMessageAndRejectsNewMessagesOnceClosingStarts() throws Exception {
        WebSocketSession raw = mock(WebSocketSession.class);
        when(raw.isOpen()).thenReturn(true);
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicInteger activeWrites = new AtomicInteger();
        AtomicInteger maximumActiveWrites = new AtomicInteger();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            TextMessage message = (TextMessage) invocation.getArgument(0, WebSocketMessage.class);
            int active = activeWrites.incrementAndGet();
            maximumActiveWrites.accumulateAndGet(active, Math::max);
            try {
                if (message.getPayload().equals("MESSAGE")) {
                    firstWriteEntered.countDown();
                    assertThat(releaseFirstWrite.await(2, TimeUnit.SECONDS)).isTrue();
                }
                events.add(message.getPayload());
                return null;
            } finally {
                activeWrites.decrementAndGet();
            }
        }).when(raw).sendMessage(any(WebSocketMessage.class));
        doAnswer(invocation -> {
            events.add("CLOSE");
            return null;
        }).when(raw).close(CloseStatus.PROTOCOL_ERROR);
        StompSerializedWebSocketSession session = new StompSerializedWebSocketSession(raw);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> message = executor.submit(() -> send(session, new TextMessage("MESSAGE")));
            assertThat(firstWriteEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> termination = executor.submit(
                    () -> sendErrorAndClose(session, new TextMessage("ERROR")));
            awaitClosing(session);

            assertThatThrownBy(() -> session.sendMessage(new TextMessage("LATE")))
                    .isInstanceOf(IOException.class);
            releaseFirstWrite.countDown();
            message.get(1, TimeUnit.SECONDS);
            termination.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdownNow();
        }

        assertThat(maximumActiveWrites).hasValue(1);
        assertThat(events).containsExactly("MESSAGE", "ERROR", "CLOSE");
    }

    @Test
    void closesPromptlyWhenAnActiveWriterCannotQuiesceForTheErrorFrame() throws Exception {
        WebSocketSession raw = mock(WebSocketSession.class);
        when(raw.isOpen()).thenReturn(true);
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> {
            writeEntered.countDown();
            releaseWrite.await(2, TimeUnit.SECONDS);
            return null;
        }).when(raw).sendMessage(any(WebSocketMessage.class));
        doAnswer(invocation -> {
            closed.countDown();
            return null;
        }).when(raw).close(CloseStatus.PROTOCOL_ERROR);
        StompSerializedWebSocketSession session = new StompSerializedWebSocketSession(raw);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> active = executor.submit(() -> send(session, new TextMessage("MESSAGE")));
        try {
            assertThat(writeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> session.sendErrorAndClose(
                    new TextMessage("ERROR"), CloseStatus.PROTOCOL_ERROR))
                    .isInstanceOf(IOException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(closed.await(100, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            releaseWrite.countDown();
            active.get(1, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    private void awaitClosing(StompSerializedWebSocketSession session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!session.isClosing() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(session.isClosing()).isTrue();
    }

    private void send(StompSerializedWebSocketSession session, TextMessage message) {
        try {
            session.sendMessage(message);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void sendErrorAndClose(StompSerializedWebSocketSession session, TextMessage message) {
        try {
            session.sendErrorAndClose(message, CloseStatus.PROTOCOL_ERROR);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
