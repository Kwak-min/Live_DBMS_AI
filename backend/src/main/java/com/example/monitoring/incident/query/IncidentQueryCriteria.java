package com.example.monitoring.incident.query;

import com.example.monitoring.partc.api.PartCPage;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;

import java.time.Instant;

record IncidentQueryCriteria(
        Long databaseConfigId,
        Instant start,
        Instant end,
        IncidentSeverity severity,
        IncidentStatus status,
        PartCPage page
) {
}
