package com.example.monitoring.common.persistence;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** PostgreSQL transaction-scoped locks for invariants spanning multiple rows. */
@Component
@RequiredArgsConstructor
public class PartBTransactionLocks {
    private static final long DATABASE_QUOTA_LOCK = 0x4C44424D53444201L;
    private static final long ACTIVE_ADMIN_LOCK = 0x4C44424D53444202L;
    private final EntityManager entityManager;

    public void lockDatabaseQuota() { lock(DATABASE_QUOTA_LOCK); }
    public void lockActiveAdminInvariant() { lock(ACTIVE_ADMIN_LOCK); }

    private void lock(long key) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:key)")
                .setParameter("key", key)
                .getSingleResult();
    }
}
