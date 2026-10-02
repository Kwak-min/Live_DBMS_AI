package com.example.monitoring.notification.stream;

import com.example.monitoring.common.stream.RedisStreamWorker;
import com.example.monitoring.common.stream.StreamWorkerSpec;
import com.example.monitoring.notification.delivery.NotificationDeliveryLoop;
import com.example.monitoring.notification.delivery.NotificationDeliveryWorker;
import com.example.monitoring.retention.PartCRetentionScheduler;
import com.example.monitoring.retention.PartCRetentionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationPipelineActivationTest {

    private static final String SOURCE_STREAM = "stream:test:incidents";
    private static final String DEAD_LETTER_STREAM = "stream:dead-letter";

    @Test
    void nonWebBootstrapCreatesNoNotificationStreamDeliveryOrRetentionRuntime() {
        configure(new ApplicationContextRunner())
                .run(context -> {
                    Map<String, Integer> counts = Map.of(
                            "stream", beanCount(context.getSourceApplicationContext()
                                    .getBeanFactory(), RedisStreamWorker.class),
                            "delivery", beanCount(context.getSourceApplicationContext()
                                    .getBeanFactory(), NotificationDeliveryLoop.class),
                            "retention", beanCount(context.getSourceApplicationContext()
                                    .getBeanFactory(), PartCRetentionScheduler.class));
                    assertThat(counts).isEqualTo(Map.of(
                            "stream", 0,
                            "delivery", 0,
                            "retention", 0));
                });
    }

    @Test
    void webRuntimeUsesConfiguredIncidentStreamAndSharedDeadLetterDefault() {
        configure(new WebApplicationContextRunner())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisStreamWorker.class);
                    assertThat(context).hasSingleBean(NotificationDeliveryLoop.class);
                    assertThat(context).hasSingleBean(PartCRetentionScheduler.class);
                    RedisStreamWorker worker = context.getBean(RedisStreamWorker.class);
                    StreamWorkerSpec spec = (StreamWorkerSpec) ReflectionTestUtils.getField(worker, "spec");
                    assertThat(spec).isNotNull();
                    assertThat(spec.sourceStream()).isEqualTo(SOURCE_STREAM);
                    assertThat(spec.deadLetterStream()).isEqualTo(DEAD_LETTER_STREAM);
                });
    }

    private <T extends org.springframework.context.ConfigurableApplicationContext>
            org.springframework.boot.test.context.runner.AbstractApplicationContextRunner<?, ?, ?> configure(
                    org.springframework.boot.test.context.runner.AbstractApplicationContextRunner<?, ?, ?> runner) {
        return runner
                .withUserConfiguration(PipelineConfiguration.class)
                .withPropertyValues(
                        "monitoring.notifications.enabled=true",
                        "monitoring.retention.enabled=true",
                        "monitoring.notifications.delivery-interval-ms=60000",
                        "app.redis.incident-stream-key=" + SOURCE_STREAM)
                .withBean("lifecycleProcessor", LifecycleProcessor.class,
                        () -> mock(LifecycleProcessor.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(NotificationIncidentEventHandler.class,
                        () -> mock(NotificationIncidentEventHandler.class))
                .withBean(NotificationDeliveryWorker.class,
                        () -> mock(NotificationDeliveryWorker.class))
                .withBean(PartCRetentionService.class,
                        () -> mock(PartCRetentionService.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(LettuceConnectionFactory.class, this::connectionFactory);
    }

    private int beanCount(
            org.springframework.beans.factory.ListableBeanFactory beanFactory,
            Class<?> type
    ) {
        return beanFactory.getBeanNamesForType(type, false, false).length;
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getRequiredNativeClient()).thenReturn(mock(RedisClient.class));
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            NotificationStreamConfiguration.class,
            NotificationDeliveryLoop.class,
            PartCRetentionScheduler.class
    })
    static class PipelineConfiguration {
    }
}
