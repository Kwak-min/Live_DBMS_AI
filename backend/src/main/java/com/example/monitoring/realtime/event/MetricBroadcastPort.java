package com.example.monitoring.realtime.event;

@FunctionalInterface
public interface MetricBroadcastPort {

    void publish(RealtimeMetricMessage message);
}
