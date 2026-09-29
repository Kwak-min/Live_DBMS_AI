package com.example.monitoring.infrastructure.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ObjectMapper는 Spring Boot 자동 구성을 사용한다. 여기서 별도 ObjectMapper 빈을 만들면
 * 자동 구성과 공통 Jackson customizer(UTC 시간 형식 등)가 모두 비활성화된다.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
