package com.example.monitoring.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 공통 시간 형식: UTC ISO 문자열 {@code YYYY-MM-DDTHH:mm:ss.SSSZ} (밀리초 3자리 고정).
 * REST 응답과 Redis 이벤트가 같은 Spring ObjectMapper를 쓰므로 둘 다 이 형식으로 직렬화된다.
 */
@Configuration
public class UtcInstantJacksonConfig {

    public static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static String format(Instant instant) {
        return UTC_MILLIS.format(instant);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer utcInstantCustomizer() {
        return builder -> builder.serializerByType(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(format(value));
            }
        });
    }
}
