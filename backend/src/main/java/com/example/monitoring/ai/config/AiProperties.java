package com.example.monitoring.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.Locale;

/**
 * AI 인사이트 설정. {@code enabled=false}이거나 선택한 제공자의 API 키가 없으면 생성 요청은 503 AI_UNAVAILABLE이고,
 * 이미 저장된 보고서 조회는 계속 동작한다.
 *
 * @param provider gemini(기본, 무료 등급 사용 가능) 또는 claude
 * @param apiKey Claude(Anthropic) API 키
 * @param geminiApiKey Gemini API 키
 * @param model 비워 두면 제공자 기본 모델
 */
@ConfigurationProperties(prefix = "monitoring.ai")
public record AiProperties(
        boolean enabled,
        String provider,
        String apiKey,
        String geminiApiKey,
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
    public static final String GEMINI = "gemini";
    public static final String CLAUDE = "claude";
    static final String DEFAULT_GEMINI_MODEL = "gemini-3.8-flash";
    static final String DEFAULT_CLAUDE_MODEL = "claude-opus-5-5";

    public AiProperties {
        provider = provider == null || provider.isBlank() ? GEMINI : provider.trim().toLowerCase(Locale.ROOT);
        if (!GEMINI.equals(provider) && !CLAUDE.equals(provider)) {
            throw new IllegalArgumentException("monitoring.ai.provider must be gemini or claude");
        }
        if (model == null || model.isBlank()) model = GEMINI.equals(provider) ? DEFAULT_GEMINI_MODEL : DEFAULT_CLAUDE_MODEL;
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

    /** 선택한 제공자의 API 키. */
    public String activeApiKey() {
        return GEMINI.equals(provider) ? geminiApiKey : apiKey;
    }

    public boolean hasApiKey() {
        String key = activeApiKey();
        return key != null && !key.isBlank();
    }

    public boolean generationAvailable() {
        return enabled && hasApiKey();
    }

    public ZoneId zone() {
        return ZoneId.of(dailyReportZone);
    }
}
