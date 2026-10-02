package com.example.monitoring.realtime.incident;

@FunctionalInterface
public interface IncidentBroadcastPort {
    void publish(RealtimeIncidentMessage message);
}
