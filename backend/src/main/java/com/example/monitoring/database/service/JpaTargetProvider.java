package com.example.monitoring.database.service;

import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.database.security.DatabaseCredentialCrypto;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.repository.DatabaseConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class JpaTargetProvider implements TargetProvider {

    private final DatabaseConfigRepository repository;
    private final DatabaseCredentialCrypto crypto;

    @Override
    @Transactional(readOnly = true)
    public List<CollectorTarget> listEnabled() {
        return repository.findByEnabledTrueAndDeletedAtIsNull().stream().map(this::decrypt).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollectorTarget> getForCollection(long databaseConfigId) {
        return repository.findByIdAndDeletedAtIsNull(databaseConfigId)
                .filter(DatabaseConfig::getEnabled)
                .map(this::decrypt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollectorTarget> getForDiagnostic(long databaseConfigId) {
        return repository.findByIdAndDeletedAtIsNull(databaseConfigId).map(this::decrypt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TargetMetadata> getMetadata(long databaseConfigId) {
        return repository.findByIdAndDeletedAtIsNull(databaseConfigId).map(config -> new TargetMetadata(
                config.getId(), config.getConfigVersion(), config.getName(), config.getHost(), config.getPort(),
                config.getDatabaseName(), config.getEnabled()));
    }

    private CollectorTarget decrypt(DatabaseConfig config) {
        requireEncrypted(config);
        String username = crypto.decrypt(config.getId(), "username", config.getUsernameKeyVersion(),
                config.getUsernameNonce(), config.getUsernameCiphertext());
        String password = crypto.decrypt(config.getId(), "password", config.getPasswordKeyVersion(),
                config.getPasswordNonce(), config.getPasswordCiphertext());
        return new CollectorTarget(config.getId(), config.getConfigVersion(), config.getName(), config.getHost(),
                config.getPort(), config.getDatabaseName(), username, password, config.getEnabled());
    }

    private void requireEncrypted(DatabaseConfig config) {
        if (config.getUsernameKeyVersion() == null || config.getUsernameNonce() == null
                || config.getUsernameCiphertext() == null || config.getPasswordKeyVersion() == null
                || config.getPasswordNonce() == null || config.getPasswordCiphertext() == null) {
            throw new IllegalStateException("Database credentials are not encrypted for target id=" + config.getId());
        }
    }
}
