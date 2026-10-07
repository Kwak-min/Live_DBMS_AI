package com.example.monitoring.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테스트도 local 프로필을 쓰므로, local 프로필이 개인 backend/.env를 읽으면 그 파일의 기능 스위치·Redis 주소가
 * 테스트에 섞인다(실제로 개발자 로컬 Redis consumer group에 테스트가 붙고 28개 테스트가 실패했다).
 * .env는 build.gradle의 bootRun만 읽는다.
 */
class LocalProfileIsolationTest {

    @Test
    void localProfileDoesNotImportDeveloperDotEnv() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application-local.yml")) {
            assertThat(in).isNotNull();
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml.lines().map(String::strip).filter(line -> !line.startsWith("#")))
                    .noneMatch(line -> line.startsWith("import:") && line.contains(".env"));
        }
    }
}
