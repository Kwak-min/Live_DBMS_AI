package com.example.monitoring.risk.heartbeat;

import com.example.monitoring.common.stream.RedisStreamWorker;
import com.example.monitoring.common.stream.StreamWorkerSpec;
import com.example.monitoring.risk.service.RiskMetricTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("riskHeartbeatStreamWorker")
@ConditionalOnProperty(prefix = "monitoring.risk", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RiskHeartbeatStreamWorker implements SmartLifecycle {

    private final RedisStreamWorker delegate;

    public RiskHeartbeatStreamWorker(
            LettuceConnectionFactory connectionFactory,
            RiskHeartbeatStreamRecordHandler handler,
            ObjectMapper objectMapper,
            @Value("${app.redis.heartbeat-stream-key:stream:collector-heartbeats}") String sourceStream,
            @Value("${monitoring.risk.dead-letter-stream:stream:dead-letter}") String deadLetterStream,
            @Value("${monitoring.risk.reclaim-min-idle:60s}") Duration reclaimMinIdle,
            @Value("${monitoring.risk.reclaim-interval:30s}") Duration reclaimInterval
    ) {
        delegate = new RedisStreamWorker(
                connectionFactory,
                new StreamWorkerSpec(
                        sourceStream,
                        RiskMetricTransaction.CONSUMER_GROUP,
                        "risk-heartbeat-consumer",
                        deadLetterStream,
                        reclaimMinIdle,
                        reclaimInterval),
                handler,
                objectMapper);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public void stop(Runnable callback) {
        delegate.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return delegate.isAutoStartup();
    }

    @Override
    public int getPhase() {
        return delegate.getPhase();
    }
}
