package com.example.monitoring.risk.redis;

import com.example.monitoring.common.stream.SharedMetricParserConfiguration;
import com.example.monitoring.risk.heartbeat.CollectorHeartbeatEventParser;
import com.example.monitoring.risk.heartbeat.HeartbeatHealthRegistry;
import com.example.monitoring.risk.heartbeat.RiskHeartbeatStreamRecordHandler;
import com.example.monitoring.risk.heartbeat.RiskHeartbeatStreamWorker;
import com.example.monitoring.risk.persistence.RiskJdbcStore;
import com.example.monitoring.risk.scheduler.RiskStaleScheduler;
import com.example.monitoring.risk.scheduler.RiskStaleTransaction;
import com.example.monitoring.risk.service.RiskMetricTransaction;
import com.example.monitoring.risk.service.RiskStartupCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskPipelineConfigurationTest {

    @Test
    void riskTrueBootsBothWorkersAndDedicatedSchedulerAfterSharedStartupReset() {
        RiskStartupCoordinator startup = mock(RiskStartupCoordinator.class);
        RiskJdbcStore store = mock(RiskJdbcStore.class);
        when(store.findStaleCandidates(any(Instant.class), anyInt())).thenReturn(List.of());

        runner(startup, store)
                .withPropertyValues(
                        "monitoring.risk.enabled=true",
                        "monitoring.risk.stale-scan-interval=1h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RiskMetricStreamWorker.class);
                    assertThat(context).hasSingleBean(RiskHeartbeatStreamWorker.class);
                    assertThat(context).hasSingleBean(RiskStaleScheduler.class);
                    assertThat(context.getBean("riskMetricStreamWorker"))
                            .isInstanceOf(RiskMetricStreamWorker.class);
                    assertThat(context.getBean("riskHeartbeatStreamWorker"))
                            .isInstanceOf(RiskHeartbeatStreamWorker.class);
                    assertThat(context.getBean("riskStaleScheduler"))
                            .isInstanceOf(RiskStaleScheduler.class);
                    verify(startup, atLeast(3)).verifyPrerequisite();
                });
    }

    @Test
    void riskFalseBootsWithoutRiskWorkersOrStaleScheduler() {
        runner(mock(RiskStartupCoordinator.class), mock(RiskJdbcStore.class))
                .withPropertyValues("monitoring.risk.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RiskMetricStreamWorker.class);
                    assertThat(context).doesNotHaveBean(RiskHeartbeatStreamWorker.class);
                    assertThat(context).doesNotHaveBean(RiskStaleScheduler.class);
                });
    }

    private WebApplicationContextRunner runner(
            RiskStartupCoordinator startup,
            RiskJdbcStore store
    ) {
        return new WebApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(RiskPipelineConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(Clock.class,
                        () -> Clock.fixed(Instant.parse("2026-10-02T00:00:30Z"), ZoneOffset.UTC))
                .withBean(RiskMetricTransaction.class, () -> mock(RiskMetricTransaction.class))
                .withBean(RiskStartupCoordinator.class, () -> startup)
                .withBean(RiskJdbcStore.class, () -> store)
                .withBean(RiskStaleTransaction.class, () -> mock(RiskStaleTransaction.class))
                .withBean(LettuceConnectionFactory.class, this::connectionFactory);
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        when(factory.getRequiredNativeClient()).thenReturn(mock(RedisClient.class));
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SharedMetricParserConfiguration.class,
            RiskMetricStreamRecordHandler.class,
            RiskMetricStreamWorker.class,
            CollectorHeartbeatEventParser.class,
            HeartbeatHealthRegistry.class,
            RiskHeartbeatStreamRecordHandler.class,
            RiskHeartbeatStreamWorker.class,
            RiskStaleScheduler.class
    })
    static class RiskPipelineConfiguration {
    }
}
