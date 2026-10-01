package com.example.monitoring.database.dto;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.domain.DatabaseConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseResponseTest {

    @Test
    void legacyLocalColumnsAreExposedAsFixedMillisecondUtcInstants() throws Exception {
        LocalDateTime local = LocalDateTime.of(2026, 9, 30, 0, 8, 51, 377_641_900);
        DatabaseConfig config = DatabaseConfig.builder().id(1L).name("db").host("localhost")
                .port(3306).enabled(true).configVersion(1L).createdAt(local).updatedAt(local)
                .lastCheckedAt(local).lastSuccessAt(local).build();
        DatabaseResponse response = DatabaseResponse.from(config);
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new UtcInstantJacksonConfig().utcInstantCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        String expected = UtcInstantJacksonConfig.format(local.atZone(ZoneId.systemDefault()).toInstant());

        assertThat(response.createdAt()).isEqualTo(local.atZone(ZoneId.systemDefault()).toInstant());
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).path("createdAt").asText())
                .isEqualTo(expected).endsWith("Z");
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).path("lastAttemptAt").asText())
                .isEqualTo(expected);
    }
}
