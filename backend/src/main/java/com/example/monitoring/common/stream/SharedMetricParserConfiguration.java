package com.example.monitoring.common.stream;

import com.example.monitoring.realtime.redis.MetricPayloadParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "monitoring.risk", name = "enabled", havingValue = "true")
public class SharedMetricParserConfiguration {

    @Bean
    @ConditionalOnMissingBean(MetricPayloadParser.class)
    MetricPayloadParser riskMetricPayloadParser(ObjectMapper objectMapper) {
        return new MetricPayloadParser(objectMapper);
    }
}
