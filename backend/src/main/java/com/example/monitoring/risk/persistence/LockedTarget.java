package com.example.monitoring.risk.persistence;

public record LockedTarget(
        long databaseConfigId,
        long configVersion,
        boolean enabled,
        boolean deleted,
        String databaseName
) {
}
