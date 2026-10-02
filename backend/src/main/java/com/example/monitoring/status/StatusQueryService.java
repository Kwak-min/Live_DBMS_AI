package com.example.monitoring.status;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.partc.api.PartCQueryValidator;
import com.example.monitoring.risk.contract.StatusSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StatusQueryService {

    private final StatusQueryRepository repository;
    private final PartCQueryValidator validator;

    public StatusQueryService(StatusQueryRepository repository, PartCQueryValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public StatusSnapshot get(String rawId) {
        long databaseConfigId = validator.requiredId(rawId, "id");
        return repository.findCurrent(databaseConfigId).orElseThrow(StatusQueryService::databaseNotFound);
    }

    private static ApiException databaseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", "DB 설정을 찾을 수 없습니다.");
    }
}
