package com.example.monitoring.common.stream;

import com.example.monitoring.realtime.redis.MetricPayloadParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineActivationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(ParserConfiguration.class);

    @Test
    void riskCanUseTheStrictMetricParserWithoutEnablingRealtime() {
        contextRunner.withPropertyValues(
                "monitoring.risk.enabled=true",
                "monitoring.realtime.enabled=false",
                "monitoring.notifications.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MetricPayloadParser.class);
                });
    }

    @Test
    void realtimeKeepsItsExistingParserWhenRiskIsDisabled() {
        contextRunner.withPropertyValues(
                "monitoring.risk.enabled=false",
                "monitoring.realtime.enabled=true",
                "monitoring.notifications.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MetricPayloadParser.class);
                });
    }

    @Test
    void notificationsAloneDoesNotActivateMetricParsing() {
        contextRunner.withPropertyValues(
                "monitoring.risk.enabled=false",
                "monitoring.realtime.enabled=false",
                "monitoring.notifications.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MetricPayloadParser.class);
                });
    }

    @Test
    void enablingRiskAndRealtimeStillCreatesOneStrictParser() {
        contextRunner.withPropertyValues(
                "monitoring.risk.enabled=true",
                "monitoring.realtime.enabled=true",
                "monitoring.notifications.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MetricPayloadParser.class);
                });
    }

    @Test
    void allThreePipelinesDefaultToDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MetricPayloadParser.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MetricPayloadParser.class, SharedMetricParserConfiguration.class})
    static class ParserConfiguration {
    }
}
