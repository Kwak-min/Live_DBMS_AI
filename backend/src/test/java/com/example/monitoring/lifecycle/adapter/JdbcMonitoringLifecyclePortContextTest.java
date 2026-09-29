package com.example.monitoring.lifecycle.adapter;

import com.example.monitoring.lifecycle.port.MonitoringLifecyclePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JdbcMonitoringLifecyclePortContextTest {

    @Test
    void springSelectsProductionConstructorWithoutStartupSql() {
        DataSource dataSource = mock(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(PlatformTransactionManager.class, () -> transactionManager)
                .withUserConfiguration(AdapterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JdbcMonitoringLifecyclePort.class);
                    assertThat(context).hasSingleBean(MonitoringLifecyclePort.class);
                    verifyNoInteractions(dataSource, transactionManager);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JdbcMonitoringLifecyclePort.class)
    static class AdapterConfiguration {
    }
}
