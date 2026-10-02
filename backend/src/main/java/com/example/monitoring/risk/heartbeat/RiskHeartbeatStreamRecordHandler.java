package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.StreamRecord;
import com.example.monitoring.common.stream.StreamRecordHandler;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "monitoring.risk", name = "enabled", havingValue = "true")
public final class RiskHeartbeatStreamRecordHandler implements StreamRecordHandler {

    private final CollectorHeartbeatEventParser parser;
    private final HeartbeatHealthRegistry registry;
    private final RiskStartupCoordinator startup;

    public RiskHeartbeatStreamRecordHandler(
            CollectorHeartbeatEventParser parser,
            HeartbeatHealthRegistry registry,
            RiskStartupCoordinator startup
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.startup = Objects.requireNonNull(startup, "startup");
    }

    @Override
    public void verifyPrerequisite() {
        startup.verifyPrerequisite();
    }

    @Override
    public void handle(StreamRecord record) {
        StreamRecord required = Objects.requireNonNull(record, "record");
        registry.accept(parser.parse(required.payload()));
    }
}
