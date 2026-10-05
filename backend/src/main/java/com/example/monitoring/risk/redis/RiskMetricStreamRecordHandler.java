package com.example.monitoring.risk.redis;

import com.example.monitoring.common.stream.InvalidStreamRecordException;
import com.example.monitoring.common.stream.InvariantStreamRecordException;
import com.example.monitoring.common.stream.StreamRecord;
import com.example.monitoring.common.stream.StreamRecordHandler;
import com.example.monitoring.realtime.event.MetricCollectedPayloadV1;
import com.example.monitoring.realtime.redis.MetricPayloadException;
import com.example.monitoring.realtime.redis.MetricPayloadParser;
import com.example.monitoring.risk.persistence.RiskPersistenceInvariantException;
import com.example.monitoring.risk.service.RiskMetricTransaction;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.risk", name = "enabled", havingValue = "true")
public final class RiskMetricStreamRecordHandler implements StreamRecordHandler {

    private final MetricPayloadParser parser;
    private final RiskMetricTransaction transaction;
    private final RiskStartupCoordinator startup;

    public RiskMetricStreamRecordHandler(
            MetricPayloadParser parser,
            RiskMetricTransaction transaction,
            RiskStartupCoordinator startup
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.startup = Objects.requireNonNull(startup, "startup");
    }

    @Override
    public void verifyPrerequisite() {
        startup.verifyPrerequisite();
    }

    @Override
    public void handle(StreamRecord record) {
        StreamRecord required = Objects.requireNonNull(record, "record");
        MetricCollectedPayloadV1 event;
        try {
            event = parser.parse(required.payload());
        } catch (MetricPayloadException exception) {
            throw new InvalidStreamRecordException(
                    exception.reasonCode(),
                    exception.getMessage(),
                    exception.eventId(),
                    exception);
        }
        try {
            transaction.process(required.sourceStream(), event);
        } catch (RiskPersistenceInvariantException exception) {
            throw new InvariantStreamRecordException(
                    "RISK_STATE_INVARIANT",
                    "Risk metric state is inconsistent",
                    event.eventId(),
                    exception);
        }
    }
}
