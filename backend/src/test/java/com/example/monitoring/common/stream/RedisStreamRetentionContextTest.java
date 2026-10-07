package com.example.monitoring.common.stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisStreamRetentionContextTest {

    @Test
    void servletApplicationEnablesRetentionByDefault() {
        webRunner().run(context ->
                assertThat(context).hasSingleBean(RedisStreamRetentionScheduler.class));
    }

    @Test
    void explicitDisableRemovesScheduler() {
        webRunner().withPropertyValues("app.redis.stream-retention.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RedisStreamRetentionScheduler.class));
    }

    @Test
    void nonWebBootstrapNeverCreatesRetentionScheduler() {
        new ApplicationContextRunner().withUserConfiguration(ConfigurationUnderTest.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> assertThat(context).doesNotHaveBean(RedisStreamRetentionScheduler.class));
    }

    private WebApplicationContextRunner webRunner() {
        return new WebApplicationContextRunner().withUserConfiguration(ConfigurationUnderTest.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RedisStreamRetentionScheduler.class)
    static class ConfigurationUnderTest {
    }
}
