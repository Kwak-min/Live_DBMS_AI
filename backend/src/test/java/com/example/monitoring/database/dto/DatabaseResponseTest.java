package com.example.monitoring.database.dto;

import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import com.example.monitoring.domain.DatabaseConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseResponseTest {

    @Test
    void timestamptzColumnsAreExposedAsFixedMillisecondUtcInstants() throws Exception {
        Instant local = Instant.parse("2026-09-29T15:08:51.377641900Z");
        DatabaseConfig config = DatabaseConfig.builder().id(1L).name("db").host("localhost")
                .port(3306).enabled(true).configVersion(1L).createdAt(local).updatedAt(local)
                .lastCheckedAt(local).lastSuccessAt(local).build();
        DatabaseResponse response = DatabaseResponse.from(config);
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new UtcInstantJacksonConfig().utcInstantCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        String expected = UtcInstantJacksonConfig.format(local);

        assertThat(response.createdAt()).isEqualTo(local);
        assertThat(expected).isEqualTo("2026-09-29T15:08:51.377Z");
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).path("createdAt").asText())
                .isEqualTo(expected).endsWith("Z");
        assertThat(mapper.readTree(mapper.writeValueAsString(response)).path("lastAttemptAt").asText())
                .isEqualTo(expected);
    }
}
