package com.example.monitoring.common.stream;

final class StreamRetryBackoff {

    private static final long[] DELAYS_MILLIS = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000};

    private final Sleeper sleeper;
    private int index;

    StreamRetryBackoff(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    void reset() {
        index = 0;
    }

    void pause() {
        long delay = DELAYS_MILLIS[Math.min(index, DELAYS_MILLIS.length - 1)];
        if (index < DELAYS_MILLIS.length - 1) {
            index++;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }
}
