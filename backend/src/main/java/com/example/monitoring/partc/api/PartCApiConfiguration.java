package com.example.monitoring.partc.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class PartCApiConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock partCClock() {
        return Clock.systemUTC();
    }
}
