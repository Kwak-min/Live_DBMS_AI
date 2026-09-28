package com.example.monitoring.database.port;

import java.util.List;
import java.util.Optional;

public interface TargetProvider {
    List<CollectorTarget> listEnabled();
    Optional<CollectorTarget> getForCollection(long databaseConfigId);
    Optional<CollectorTarget> getForDiagnostic(long databaseConfigId);
    Optional<TargetMetadata> getMetadata(long databaseConfigId);
}
