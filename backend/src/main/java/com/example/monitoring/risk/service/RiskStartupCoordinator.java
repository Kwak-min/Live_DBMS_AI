package com.example.monitoring.risk.service;

import com.example.monitoring.risk.persistence.RiskJdbcStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Component
public class RiskStartupCoordinator {

    private final RiskJdbcStore store;
    private final TransactionTemplate transaction;
    private boolean completed;

    public RiskStartupCoordinator(
            RiskJdbcStore store,
            PlatformTransactionManager transactionManager
    ) {
        this.store = Objects.requireNonNull(store, "store");
        transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public synchronized void verifyPrerequisite() {
        if (completed) {
            return;
        }
        transaction.executeWithoutResult(status -> store.clearTransientRuleClocks());
        completed = true;
    }
}
