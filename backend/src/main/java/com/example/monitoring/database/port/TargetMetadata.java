package com.example.monitoring.database.port;

public record TargetMetadata(
        long id,
        long configVersion,
        String name,
        String host,
        int port,
        String databaseName,
        boolean enabled
) {
}
