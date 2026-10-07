package com.example.monitoring.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * AI 인사이트 설정. {@code enabled=false}이거나 API 키가 없으면 생성 요청은 503 AI_UNAVAILABLE이고,
 * 이미 저장된 보고서 조회는 계속 동작한다.
 */
@ConfigurationProperties(prefix = "monitoring.ai")
public record AiProperties(
        boolean enabled,
        String apiKey,
        String model,
        String effort,
        int maxOutputTokens,
        String dailyReportZone,
        boolean dailyReportScheduleEnabled,
        String dailyReportCron,
        int requestCooldownSeconds,
        int maxQueriesPerAnalysis,
        int retentionDays,
        int workerThreads,
        int workerQueueCapacity,
        boolean refusalFallback
) {
    public AiProperties {
        if (model == null || model.isBlank()) model = "claude-opus-5-5";
        if (effort == null || effort.isBlank()) effort = "medium";
        if (maxOutputTokens <= 0) maxOutputTokens = 16_000;
        if (dailyReportZone == null || dailyReportZone.isBlank()) dailyReportZone = "UTC";
        if (dailyReportCron == null || dailyReportCron.isBlank()) dailyReportCron = "0 10 0 * * *";
        if (requestCooldownSeconds <= 0) requestCooldownSeconds = 60;
        if (maxQueriesPerAnalysis <= 0) maxQueriesPerAnalysis = 20;
        if (retentionDays <= 0) retentionDays = 180;
        if (workerThreads <= 0) workerThreads = 2;
        if (workerQueueCapacity <= 0) workerQueueCapacity = 50;
        ZoneId.of(dailyReportZone);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean generationAvailable() {
        return enabled && hasApiKey();
    }

    public ZoneId zone() {
        return ZoneId.of(dailyReportZone);
    }
}
