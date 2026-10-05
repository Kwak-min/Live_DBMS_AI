package com.example.monitoring.risk.contract;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record StatusSnapshot(
        long databaseConfigId,
        long configVersion,
        boolean deleted,
        boolean enabled,
        ConnectionStatus connectionStatus,
        DataFreshness dataFreshness,
        RiskLevel riskLevel,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        Long latestMetricId,
        List<UUID> openIncidentIds,
        long stateVersion,
        Instant updatedAt
) {
    public StatusSnapshot {
        ContractChecks.safeId(databaseConfigId, "databaseConfigId");
        ContractChecks.safeId(configVersion, "configVersion");
        ContractChecks.safeId(stateVersion, "stateVersion");
        ContractChecks.nullableSafeId(latestMetricId, "latestMetricId");
        Objects.requireNonNull(connectionStatus, "connectionStatus");
        Objects.requireNonNull(dataFreshness, "dataFreshness");
        lastAttemptAt = ContractChecks.nullableMillis(lastAttemptAt);
        lastSuccessAt = ContractChecks.nullableMillis(lastSuccessAt);
        updatedAt = ContractChecks.millis(updatedAt, "updatedAt");
        if (lastSuccessAt != null && lastAttemptAt != null && lastSuccessAt.isAfter(lastAttemptAt)) {
            throw new IllegalArgumentException("lastSuccessAt cannot be after lastAttemptAt");
        }
        if ((!enabled || deleted) && (dataFreshness != DataFreshness.PAUSED || riskLevel != null)) {
            throw new IllegalArgumentException("Disabled or deleted status must be PAUSED with null riskLevel");
        }
        List<UUID> ids = new ArrayList<>(Objects.requireNonNull(openIncidentIds, "openIncidentIds"));
        if (ids.stream().anyMatch(Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("openIncidentIds must be unique and non-null");
        }
        ids.sort(Comparator.comparing(UUID::toString));
        openIncidentIds = List.copyOf(ids);
    }
}
