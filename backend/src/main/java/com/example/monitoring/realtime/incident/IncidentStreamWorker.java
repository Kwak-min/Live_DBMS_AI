package com.example.monitoring.realtime.incident;

import com.example.monitoring.common.stream.RedisStreamWorker;
import com.example.monitoring.common.stream.StreamWorkerSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IncidentStreamWorker implements SmartLifecycle {

    private final RedisStreamWorker delegate;

    public IncidentStreamWorker(
            LettuceConnectionFactory connectionFactory,
            IncidentStreamRecordHandler handler,
            ObjectMapper objectMapper,
            @Value("${app.redis.incident-stream-key:stream:incidents}") String sourceStream,
            @Value("${monitoring.realtime.dead-letter-stream:stream:dead-letter}") String deadLetterStream,
            @Value("${monitoring.realtime.reclaim-min-idle:60s}") Duration reclaimMinIdle,
            @Value("${monitoring.realtime.reclaim-interval:30s}") Duration reclaimInterval
    ) {
        delegate = new RedisStreamWorker(
                connectionFactory,
                new StreamWorkerSpec(
                        sourceStream,
                        RealtimeIncidentTransaction.CONSUMER_GROUP,
                        "realtime-incident-consumer",
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
