package com.example.monitoring.incident.query;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.partc.api.PartCPage;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.partc.api.PartCQueryWindow;
import com.example.monitoring.risk.contract.Incident;
import com.example.monitoring.risk.contract.IncidentSeverity;
import com.example.monitoring.risk.contract.IncidentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

    private final IncidentQueryRepository repository;
    private final PartCQueryValidator validator;

    public IncidentQueryService(IncidentQueryRepository repository, PartCQueryValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public PageResponse<Incident> list(
            String rawDatabaseConfigId,
            String rawStart,
            String rawEnd,
            String rawSeverity,
            String rawStatus,
            String rawPage,
            String rawSize
    ) {
        Long databaseConfigId = validator.optionalId(rawDatabaseConfigId, "databaseConfigId");
        PartCQueryWindow window = validator.window(rawStart, rawEnd);
        IncidentSeverity severity = validator.optionalEnum(rawSeverity, IncidentSeverity.class, "severity");
        IncidentStatus status = validator.optionalEnum(rawStatus, IncidentStatus.class, "status");
        PartCPage page = validator.page(rawPage, rawSize);
        return repository.findAll(new IncidentQueryCriteria(
                databaseConfigId,
                window.start(),
                window.end(),
                severity,
                status,
                page));
    }

    public Incident get(String rawIncidentId) {
        UUID incidentId = validator.requiredUuid(rawIncidentId, "incidentId");
        return repository.findById(incidentId).orElseThrow(IncidentQueryService::incidentNotFound);
    }

    private static ApiException incidentNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "사건을 찾을 수 없습니다.");
    }
}
