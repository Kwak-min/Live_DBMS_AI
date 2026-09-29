package com.example.monitoring.realtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisMetricRecordProcessorContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("monitoring.realtime.enabled=true")
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RealtimeMetricTransaction.class, () -> mock(RealtimeMetricTransaction.class))
            .withBean(RealtimeRedisSettings.class, () -> new RealtimeRedisSettings(
                    "test:metrics", "test:dead-letter", Duration.ofSeconds(60), Duration.ofSeconds(30)))
            .withUserConfiguration(ProcessorConfiguration.class);

    @Test
    void springSelectsTheProductionProcessorConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MetricPayloadParser.class);
            assertThat(context).hasSingleBean(RedisMetricRecordProcessor.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MetricPayloadParser.class, RedisMetricRecordProcessor.class})
    static class ProcessorConfiguration {
    }
}
