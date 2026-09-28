package com.example.monitoring.service;

import com.example.monitoring.collector.MariaDbHealthChecker;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.dto.DbPingResponseDto;
import com.example.monitoring.repository.DatabaseConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DatabaseHealthService {

    private final DatabaseConfigRepository databaseConfigRepository;
    private final MariaDbHealthChecker mariaDbHealthChecker;

    @Transactional
    public Optional<DbPingResponseDto> pingDatabase(Long databaseConfigId) {
        Optional<DatabaseConfig> configOpt = databaseConfigRepository.findById(databaseConfigId);
        if (configOpt.isEmpty()) {
            return Optional.empty();
        }

        DatabaseConfig config = configOpt.get();
        DbPingResponseDto pingResult = mariaDbHealthChecker.pingAndFetchVersion(config);

        config.setStatus(pingResult.getStatus());
        config.setLastCheckedAt(pingResult.getTimestamp());
        config.setLastErrorMessage(pingResult.getErrorMessage());
        databaseConfigRepository.save(config);

        return Optional.of(pingResult);
    }
}
