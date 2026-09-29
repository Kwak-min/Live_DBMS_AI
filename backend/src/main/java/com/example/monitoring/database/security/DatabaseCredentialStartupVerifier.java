package com.example.monitoring.database.security;

import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.repository.DatabaseConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.database-security.verify-on-startup", havingValue = "true", matchIfMissing = true)
public class DatabaseCredentialStartupVerifier implements ApplicationRunner {

    private final DatabaseConfigRepository repository;
    private final DatabaseCredentialCrypto crypto;

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        for (DatabaseConfig config : repository.findAllByOrderByIdAsc()) {
            verify(config);
        }
    }

    private void verify(DatabaseConfig config) {
        if (config.getUsernameKeyVersion() == null || config.getUsernameNonce() == null
                || config.getUsernameCiphertext() == null || config.getPasswordKeyVersion() == null
                || config.getPasswordNonce() == null || config.getPasswordCiphertext() == null) {
            throw new IllegalStateException(
                    "Refusing startup: incomplete encrypted credentials for database_config id=" + config.getId());
        }
        crypto.decrypt(config.getId(), "username", config.getUsernameKeyVersion(),
                config.getUsernameNonce(), config.getUsernameCiphertext());
        crypto.decrypt(config.getId(), "password", config.getPasswordKeyVersion(),
                config.getPasswordNonce(), config.getPasswordCiphertext());
    }
}
