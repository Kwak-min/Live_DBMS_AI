package com.example.monitoring.notification.stream;

import com.example.monitoring.common.stream.RedisStreamWorker;
import com.example.monitoring.common.stream.StreamWorkerSpec;
import com.example.monitoring.notification.scheduling.NotificationSchedulingTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "monitoring.notifications",
        name = "enabled",
        havingValue = "true")
public class NotificationStreamConfiguration {

    @Bean
    RedisStreamWorker notificationIncidentStreamWorker(
            LettuceConnectionFactory connectionFactory,
            NotificationIncidentEventHandler handler,
            ObjectMapper objectMapper,
            @Value("${app.redis.incident-stream-key:stream:incidents}") String sourceStream,
            @Value("${monitoring.notifications.dead-letter-stream:stream:dead-letter}")
            String deadLetterStream
    ) {
        return new RedisStreamWorker(
                connectionFactory,
                new StreamWorkerSpec(
                        sourceStream,
                        NotificationSchedulingTransaction.CONSUMER_GROUP,
                        "notification-incident-stream-worker",
                        deadLetterStream),
                handler,
                objectMapper);
    }
}
