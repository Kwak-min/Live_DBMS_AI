package com.example.monitoring.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 검수 시나리오 T06: 대상 DB 계정이 드라이버 오류 로그로 새지 않도록 한 설정을 지킨다. */
class TargetDriverLoggingConfigTest {

    @Test
    @DisplayName("MariaDB driver server-error logging (contains the target account) is turned off")
    void mariaDbErrorPacketLoggingIsOff() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources).anySatisfy(source -> assertThat(
                String.valueOf(source.getProperty("logging.level.org.mariadb.jdbc.message.server.ErrorPacket")))
                .isEqualToIgnoringCase("OFF"));
    }
}
