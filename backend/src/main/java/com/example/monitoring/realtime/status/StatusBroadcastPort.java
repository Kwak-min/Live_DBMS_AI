package com.example.monitoring.realtime.status;

@FunctionalInterface
public interface StatusBroadcastPort {
    void publish(RealtimeStatusMessage message);
}
