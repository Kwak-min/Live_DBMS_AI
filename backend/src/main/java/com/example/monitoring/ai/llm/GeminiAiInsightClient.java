package com.example.monitoring.ai.llm;

import com.example.monitoring.ai.config.AiProperties;
import com.example.monitoring.ai.model.DailyReportInsight;
import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.QueryAnalysisInsight;
import com.example.monitoring.ai.model.QuerySample;
import com.example.monitoring.ai.model.QuerySampleSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gemini API 구현({@code monitoring.ai.provider=gemini}, 기본). Google AI Studio 무료 등급 키로 쓸 수 있다.
 * 무료 등급은 입력이 Google 제품 개선에 쓰일 수 있으므로 보내는 범위는 {@link AiPrompts}와 같게 제한한다.
 * 응답은 JSON Schema 구조화 출력으로 받아 레코드로 역직렬화한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitoring.ai", name = "provider", havingValue = AiProperties.GEMINI,
        matchIfMissing = true)
public class GeminiAiInsightClient implements AiInsightClient {

    /** 다른 모델로 넘어가 다시 시도할 만한 실패. 키 오류·안전 차단·길이 초과는 모델을 바꿔도 같으므로 제외한다. */
    private static final Set<String> FALLBACK_CODES = Set.of(
            AiGenerationException.RATE_LIMITED, AiGenerationException.UPSTREAM_ERROR,
            AiGenerationException.INVALID_OUTPUT);
    private static final Set<FinishReason.Known> BLOCKED = Set.of(
            FinishReason.Known.SAFETY, FinishReason.Known.PROHIBITED_CONTENT, FinishReason.Known.BLOCKLIST,
            FinishReason.Known.SPII, FinishReason.Known.RECITATION);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> dailySchema;
    private final Map<String, Object> querySchema;
    private volatile Client client;

    public GeminiAiInsightClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.dailySchema = AiOutputSchemas.daily(objectMapper);
        this.querySchema = AiOutputSchemas.query(objectMapper);
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
                dailySchema, DailyReportInsight.class);
    }

    @Override
    public Result<QueryAnalysisInsight> queryAnalysis(String databaseName, QuerySampleSource source,
                                                      List<QuerySample> samples) {
        return call(AiPrompts.QUERY_SYSTEM, AiPrompts.queryUser(objectMapper, databaseName, source, samples),
                querySchema, QueryAnalysisInsight.class);
    }

    /** 기본 모델부터 차례로 시도한다. 무료 등급 모델은 혼잡(503)·한도(429)·단종(404)이 잦다. */
    private <T> Result<T> call(String system, String user, Map<String, Object> schema, Class<T> outputType) {
        AiGenerationException last = null;
        for (String model : properties.modelCandidates()) {
            try {
                return attempt(model, system, user, schema, outputType);
            } catch (AiGenerationException e) {
                if (!FALLBACK_CODES.contains(e.code())) throw e;
                log.warn("Gemini model failed; trying next model if any. model={}, code={}", model, e.code());
                last = e;
            }
        }
        throw last;
    }

    private <T> Result<T> attempt(String model, String system, String user, Map<String, Object> schema,
                                  Class<T> outputType) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(system)))
                .responseMimeType("application/json")
                .responseJsonSchema(schema)
                .maxOutputTokens(properties.maxOutputTokens())
                .build();

        GenerateContentResponse response;
        try {
            response = client().models.generateContent(model, user, config);
        } catch (ApiException e) {
            throw translate(e);
        } catch (GenAiIOException e) {
            throw new AiGenerationException(AiGenerationException.UPSTREAM_ERROR,
                    "AI 서비스에 연결하지 못했거나 응답 시간이 초과되었습니다.", e);
        }

        boolean promptBlocked = response.promptFeedback()
                .flatMap(feedback -> feedback.blockReason())
                .isPresent();
        if (promptBlocked || response.candidates().map(List::isEmpty).orElse(true)) {
            throw new AiGenerationException(AiGenerationException.REFUSED, "AI가 이 요청에 대한 응답을 거절했습니다.");
        }
        FinishReason.Known finish = response.finishReason().knownEnum();
        if (finish == FinishReason.Known.MAX_TOKENS) {
            throw new AiGenerationException(AiGenerationException.TRUNCATED, "AI 응답이 길이 제한으로 잘렸습니다.");
        }
        if (BLOCKED.contains(finish)) {
            throw new AiGenerationException(AiGenerationException.REFUSED, "AI가 이 요청에 대한 응답을 거절했습니다.");
        }

        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new AiGenerationException(AiGenerationException.INVALID_OUTPUT, "AI 응답에 결과가 없습니다.");
        }
        T value;
        try {
            value = objectMapper.readValue(text, outputType);
        } catch (JsonProcessingException e) {
            throw new AiGenerationException(AiGenerationException.INVALID_OUTPUT, "AI 응답을 해석하지 못했습니다.", e);
        }
        long input = response.usageMetadata().flatMap(GenerateContentResponseUsageMetadata::promptTokenCount)
                .orElse(0);
        long output = response.usageMetadata().flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount)
                .orElse(0)
                + response.usageMetadata().flatMap(GenerateContentResponseUsageMetadata::thoughtsTokenCount).orElse(0);
        return new Result<>(value, input, output, model);
    }

    private static AiGenerationException translate(ApiException e) {
        String message = e.message() == null ? "" : e.message().toLowerCase(Locale.ROOT);
        if (e.code() == 429) {
            return new AiGenerationException(AiGenerationException.RATE_LIMITED,
                    "AI 요청 한도(무료 등급 포함)를 넘었습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        // Gemini는 잘못된 키를 400 INVALID_ARGUMENT로 돌려준다.
        if (e.code() == 401 || e.code() == 403 || message.contains("api key")) {
            return new AiGenerationException(AiGenerationException.AUTH_FAILED,
                    "AI API 키가 유효하지 않거나 권한이 없습니다.", e);
        }
        log.warn("Gemini API error. status={}, apiStatus={}", e.code(), e.status());
        return new AiGenerationException(AiGenerationException.UPSTREAM_ERROR,
                "AI 서비스 오류로 생성하지 못했습니다.", e);
    }

    private Client client() {
        Client current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client == null) {
                if (!properties.hasApiKey()) {
                    throw new AiGenerationException(AiGenerationException.UNAVAILABLE, "AI API 키가 설정되지 않았습니다.");
                }
                // SDK 재시도는 끄고(시도 1회) 모델 전환으로 대신한다. 재시도가 겹치면 대기 시간이 몇 배가 된다.
                client = Client.builder()
                        .apiKey(properties.activeApiKey())
                        .httpOptions(HttpOptions.builder()
                                .timeout(properties.requestTimeoutSeconds() * 1000)
                                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                                .build())
                        .build();
            }
            return client;
        }
    }

    @PreDestroy
    void close() {
        Client current = client;
        if (current != null) current.close();
    }
}
