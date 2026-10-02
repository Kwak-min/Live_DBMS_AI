package com.example.monitoring.realtime.incident;

import com.example.monitoring.common.stream.StreamRecord;
import com.example.monitoring.common.stream.StreamRecordHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public final class IncidentStreamRecordHandler implements StreamRecordHandler {

    private final IncidentStreamEventParser parser;
    private final RealtimeIncidentTransaction transaction;

    public IncidentStreamRecordHandler(
            IncidentStreamEventParser parser,
            RealtimeIncidentTransaction transaction
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    @Override
    public void verifyPrerequisite() {
        transaction.verifyPrerequisite();
    }

    @Override
    public void handle(StreamRecord record) {
        StreamRecord required = Objects.requireNonNull(record, "record");
        transaction.process(required.sourceStream(), parser.parse(required.payload()));
    }
}
