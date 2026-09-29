package com.example.monitoring.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * JPA 통합 테스트용 내장 PostgreSQL 16. JVM당 한 번 시작하며 Spring 테스트 컨텍스트가 같은 인스턴스를 공유한다.
 * {@code @Import(EmbeddedPostgresSupport.Config.class)}와
 * {@code @AutoConfigureTestDatabase(replace = NONE)}로 사용한다. Flyway가 전체 migration을 적용한다.
 */
public final class EmbeddedPostgresSupport {

    /** 파트별 migration 입력(V2 B, V3 A). 테스트 전용 값이며 실제 키가 아니다. */
    public static final Map<String, String> MIGRATION_PROPERTIES = Map.of(
            "DB_CONFIG_ACTIVE_KEY_VERSION", "1",
            "DB_CONFIG_ENCRYPTION_KEYS", "{\"1\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"}",
            "LEGACY_TIME_ZONE", "Asia/Seoul");

    private static EmbeddedPostgres postgres;

    private EmbeddedPostgresSupport() {
    }

    public static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
            MIGRATION_PROPERTIES.forEach(System::setProperty);
            try {
                postgres = EmbeddedPostgres.start();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // JVM 종료 중이므로 무시한다.
                }
            }));
        }
        return postgres;
    }

    @TestConfiguration
    public static class Config {
        @Bean
        DataSource dataSource() {
            return postgres().getPostgresDatabase();
        }
    }
}
