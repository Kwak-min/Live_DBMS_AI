package com.example.monitoring.realtime.stomp;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class StompSerializedWebSocketSession extends WebSocketSessionDecorator {
    static final Duration ERROR_WRITE_LOCK_TIMEOUT = Duration.ofMillis(250);
    private static final Duration MESSAGE_WRITE_LOCK_TIMEOUT = Duration.ofSeconds(10);

    private final ReentrantLock sendLock = new ReentrantLock(true);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean closeIssued = new AtomicBoolean();

    StompSerializedWebSocketSession(WebSocketSession delegate) {
        super(delegate);
    }

    @Override
    public boolean isOpen() {
        return !closing.get() && super.isOpen();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        if (closing.get()) {
            throw new IOException("WebSocket session is closing");
        }
        boolean acquired = acquire(MESSAGE_WRITE_LOCK_TIMEOUT);
        if (!acquired) {
            throw new IOException("WebSocket send did not become available");
        }
        try {
            if (closing.get()) {
                throw new IOException("WebSocket session is closing");
            }
            super.sendMessage(message);
        } finally {
            sendLock.unlock();
        }
    }

    void beginClosing() {
        closing.set(true);
    }

    boolean isClosing() {
        return closing.get();
    }

    void sendErrorAndClose(WebSocketMessage<?> error, CloseStatus status) throws IOException {
        beginClosing();
        boolean acquired = false;
        IOException failure = null;
        try {
            acquired = acquire(ERROR_WRITE_LOCK_TIMEOUT);
            if (!acquired) {
                failure = new IOException("WebSocket send did not quiesce");
            } else if (super.isOpen()) {
                super.sendMessage(error);
            }
        } catch (IOException writeFailure) {
            failure = writeFailure;
        } finally {
            try {
                closeDelegate(status);
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            } finally {
                if (acquired) {
                    sendLock.unlock();
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        beginClosing();
        closeDelegate(null);
    }

    @Override
    public void close(CloseStatus status) throws IOException {
        beginClosing();
        closeDelegate(status);
    }

    private boolean acquire(Duration timeout) throws IOException {
        try {
            return sendLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while serializing WebSocket send", interrupted);
        }
    }

    private void closeDelegate(CloseStatus status) throws IOException {
        if (!closeIssued.compareAndSet(false, true)) {
            return;
        }
        if (status == null) {
            super.close();
        } else {
            super.close(status);
        }
    }
}
