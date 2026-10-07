package com.example.monitoring.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.example.monitoring.ai.config.AiProperties;
import com.example.monitoring.ai.model.DailyReportInsight;
import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.QueryAnalysisInsight;
import com.example.monitoring.ai.model.QuerySample;
import com.example.monitoring.ai.model.QuerySampleSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Claude API 구현({@code monitoring.ai.provider=claude}). 프롬프트는 {@link AiPrompts}를 쓴다.
 * 응답은 구조화 출력(JSON schema)으로 받아 레코드로 바로 역직렬화한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitoring.ai", name = "provider", havingValue = AiProperties.CLAUDE)
public class ClaudeAiInsightClient implements AiInsightClient {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";



    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private volatile AnthropicClient client;

    public ClaudeAiInsightClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public Result<DailyReportInsight> dailyReport(String databaseName, LocalDate reportDate, String timeZone,
                                                  DailyStats stats, DailyStats previousDayStats) {
        return call(AiPrompts.DAILY_SYSTEM,
                AiPrompts.dailyUser(objectMapper, databaseName, reportDate, timeZone, stats, previousDayStats),
                DailyReportInsight.class);
    }

    @Override
    public Result<QueryAnalysisInsight> queryAnalysis(String databaseName, QuerySampleSource source,
                                                      List<QuerySample> samples) {
        return call(AiPrompts.QUERY_SYSTEM, AiPrompts.queryUser(objectMapper, databaseName, source, samples),
                QueryAnalysisInsight.class);
    }

    private <T> Result<T> call(String system, String user, Class<T> outputType) {
        StructuredMessageCreateParams.Builder<T> builder = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxOutputTokens())
                .system(system)
                .addUserMessage(user)
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(OutputConfig.Effort.of(properties.effort().toLowerCase(Locale.ROOT)))
                        .format(outputType)
                        .build());
        if (properties.refusalFallback()) {
            // 안전 분류기가 거절하면 서버가 권장 대체 모델로 다시 실행한다.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        StructuredMessage<T> response;
        try {
            response = client().messages().create(builder.build());
        } catch (RateLimitException e) {
            throw new AiGenerationException(AiGenerationException.RATE_LIMITED,
                    "AI 요청 한도를 넘었습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new AiGenerationException(AiGenerationException.AUTH_FAILED,
                    "AI API 키가 유효하지 않거나 권한이 없습니다.", e);
        } catch (BadRequestException e) {
            log.warn("Claude rejected AI request. status={}", e.statusCode());
            throw new AiGenerationException(AiGenerationException.UPSTREAM_ERROR,
                    "AI 요청 형식이 거절되었습니다.", e);
        } catch (AnthropicServiceException e) {
            log.warn("Claude API error. status={}", e.statusCode());
            throw new AiGenerationException(AiGenerationException.UPSTREAM_ERROR,
                    "AI 서비스 오류로 생성하지 못했습니다.", e);
        } catch (AnthropicIoException e) {
            throw new AiGenerationException(AiGenerationException.UPSTREAM_ERROR,
                    "AI 서비스에 연결하지 못했습니다.", e);
        }

        StopReason stopReason = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            throw new AiGenerationException(AiGenerationException.REFUSED, "AI가 이 요청에 대한 응답을 거절했습니다.");
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)
                || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stopReason)) {
            throw new AiGenerationException(AiGenerationException.TRUNCATED, "AI 응답이 길이 제한으로 잘렸습니다.");
        }
        try {
            T value = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new AiGenerationException(AiGenerationException.INVALID_OUTPUT,
                            "AI 응답에 결과가 없습니다."));
            return new Result<>(value, response.usage().inputTokens(), response.usage().outputTokens(),
                    properties.model());
        } catch (AnthropicInvalidDataException e) {
            throw new AiGenerationException(AiGenerationException.INVALID_OUTPUT,
                    "AI 응답을 해석하지 못했습니다.", e);
        }
    }

    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client == null) {
                if (!properties.hasApiKey()) {
                    throw new AiGenerationException(AiGenerationException.UNAVAILABLE, "AI API 키가 설정되지 않았습니다.");
                }
                client = AnthropicOkHttpClient.builder()
                        .apiKey(properties.activeApiKey())
                        .timeout(Duration.ofMinutes(5))
                        .maxRetries(2)
                        .build();
            }
            return client;
        }
    }

}
