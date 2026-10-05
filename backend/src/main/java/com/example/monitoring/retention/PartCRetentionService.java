package com.example.monitoring.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
public class PartCRetentionService {

    private static final int DELIVERY_RETENTION_DAYS = 30;
    private static final int INCIDENT_RETENTION_DAYS = 180;

    private final PartCRetentionStore store;
    private final TransactionTemplate batchTransaction;
    private final int batchSize;
    private final int maxBatches;

    public PartCRetentionService(
            PartCRetentionStore store,
            PlatformTransactionManager transactionManager,
            @Value("${monitoring.retention.batch-size:500}") int batchSize,
            @Value("${monitoring.retention.max-batches:20}") int maxBatches
    ) {
        this.store = Objects.requireNonNull(store, "store");
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("monitoring.retention.batch-size must be 1..10000");
        }
        if (maxBatches < 1 || maxBatches > 1_000) {
            throw new IllegalArgumentException("monitoring.retention.max-batches must be 1..1000");
        }
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        batchTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CleanupResult purge(Instant now) {
        Instant boundary = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MILLIS);
        Instant deliveryCutoff = boundary.minus(DELIVERY_RETENTION_DAYS, ChronoUnit.DAYS);
        Instant incidentCutoff = boundary.minus(INCIDENT_RETENTION_DAYS, ChronoUnit.DAYS);
        int deliveries = 0;
        int incidents = 0;
        int batches = 0;

        while (batches < maxBatches) {
            PartCRetentionStore.BatchResult batch = Objects.requireNonNull(
                    batchTransaction.execute(status ->
                            store.deleteBatch(deliveryCutoff, incidentCutoff, batchSize)));
            if (batch.total() == 0) {
                break;
            }
            deliveries += batch.deliveries();
            incidents += batch.incidents();
            batches++;
        }
        return new CleanupResult(deliveries, incidents, batches);
    }

    public record CleanupResult(int deliveries, int incidents, int batches) {
    }
}
