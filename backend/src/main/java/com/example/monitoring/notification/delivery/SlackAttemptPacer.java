package com.example.monitoring.notification.delivery;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Component
public final class SlackAttemptPacer {

    private static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Map<DigestKey, Long> lastCompletionByUrl = new HashMap<>();

    public boolean awaitPermit(String canonicalIdentity, long leaseAcquiredNanoTime) {
        DigestKey key = DigestKey.of(canonicalIdentity);
        long deadline;
        synchronized (lastCompletionByUrl) {
            long now = System.nanoTime();
            long firstAllowed = leaseAcquiredNanoTime + INTERVAL_NANOS;
            Long previous = lastCompletionByUrl.get(key);
            long recipientAllowed = previous == null ? firstAllowed : previous + INTERVAL_NANOS;
            deadline = later(now, later(firstAllowed, recipientAllowed));
        }
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return true;
            }
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    public void recordCompletion(String canonicalIdentity) {
        DigestKey key = DigestKey.of(canonicalIdentity);
        synchronized (lastCompletionByUrl) {
            lastCompletionByUrl.put(key, System.nanoTime());
        }
    }

    private long later(long left, long right) {
        return left - right >= 0 ? left : right;
    }

    private static final class DigestKey {
        private final byte[] value;

        private DigestKey(byte[] value) {
            this.value = value;
        }

        static DigestKey of(String canonicalIdentity) {
            if (canonicalIdentity == null || canonicalIdentity.isBlank()) {
                throw new IllegalArgumentException("Canonical Slack identity is required.");
            }
            try {
                return new DigestKey(MessageDigest.getInstance("SHA-256")
                        .digest(canonicalIdentity.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable.", exception);
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DigestKey key && MessageDigest.isEqual(value, key.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "DigestKey[redacted]";
        }
    }
}
