package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.StompAccessTokenAuthenticator;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.realtime.event.MetricBroadcastPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StompTransportContextTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("app.auth.public-origin=https://monitor.example");

    @Test
    void createsTheCompleteTransportGraphOnlyWhenRealtimeIsEnabled() {
        contextRunner.withPropertyValues("monitoring.realtime.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StompSessionRegistry.class);
            assertThat(context).hasSingleBean(StompSecurityChannelInterceptor.class);
            assertThat(context).hasSingleBean(StompTransportConfiguration.class);
            assertThat(context).hasSingleBean(MetricBroadcastPort.class);
            assertThat(context).hasBean("stompTaskScheduler");
        });
    }

    @Test
    void leavesNoTransportOrPublisherBeanWhenRealtimeIsDisabled() {
        contextRunner.withPropertyValues("monitoring.realtime.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(StompSessionRegistry.class);
            assertThat(context).doesNotHaveBean(StompTransportConfiguration.class);
            assertThat(context).doesNotHaveBean(MetricBroadcastPort.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            StompSchedulerConfiguration.class,
            StrictOriginHandshakeInterceptor.class,
            StompSessionRegistry.class,
            BrokerStompSubscriptionErrorSender.class,
            StompProtocolErrorHandler.class,
            StompSecurityChannelInterceptor.class,
            StompOutboundSecurityInterceptor.class,
            StompTransportConfiguration.class,
            StompMetricBroadcastAdapter.class
    })
    static class TestConfiguration {
        @Bean
        StompAccessTokenAuthenticator stompAccessTokenAuthenticator() {
            return mock(StompAccessTokenAuthenticator.class);
        }

        @Bean
        TargetProvider targetProvider() {
            return mock(TargetProvider.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
