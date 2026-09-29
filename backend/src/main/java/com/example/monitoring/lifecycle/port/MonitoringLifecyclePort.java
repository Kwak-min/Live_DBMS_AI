package com.example.monitoring.lifecycle.port;

@FunctionalInterface
public interface MonitoringLifecyclePort {
    void applyChange(TargetChange change);
}
