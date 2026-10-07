package com.example.monitoring.ai.service;

import com.example.monitoring.ai.collect.DailyStatsRepository;
import com.example.monitoring.ai.collect.TargetQuerySampler;
import com.example.monitoring.ai.config.AiProperties;
import com.example.monitoring.ai.llm.AiGenerationException;
import com.example.monitoring.ai.llm.AiInsightClient;
import com.example.monitoring.ai.model.AiReportStatus;
import com.example.monitoring.ai.model.AiReportType;
import com.example.monitoring.ai.model.AiTriggerSource;
import com.example.monitoring.ai.model.AnalyzedQuery;
import com.example.monitoring.ai.model.DailyReport;
import com.example.monitoring.ai.model.DailyReportInsight;
import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.QueryAnalysis;
import com.example.monitoring.ai.model.QueryAnalysisInsight;
import com.example.monitoring.ai.model.QueryInsight;
import com.example.monitoring.ai.model.QueryRiskLevel;
import com.example.monitoring.ai.model.QuerySample;
import com.example.monitoring.ai.service.AiReportStore.AiReportRow;
import com.example.monitoring.ai.web.AiReportResponse;
import com.example.monitoring.ai.web.AiStatusResponse;
import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.api.PageResponse;
import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.database.port.TargetMetadata;
import com.example.monitoring.database.port.TargetProvider;
import com.example.monitoring.database.security.DatabaseCredentialUnavailableException;
import com.example.monitoring.domain.AuditAction;
import com.example.monitoring.domain.AuditTargetType;
import com.example.monitoring.service.AuditEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 보고서 요청·생성·조회. 요청은 PENDING 행을 만들고 즉시 돌려주며(202), 생성은 전용 스레드에서 끝나면
 * SUCCEEDED/FAILED로 바뀐다. 프론트는 GET /api/v1/ai/reports/{id}로 상태를 확인한다.
 */
@Slf4j
@Service
public class AiReportService {

    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String TARGET_UNREACHABLE = "TARGET_UNREACHABLE";
    static final String CREDENTIALS_UNAVAILABLE = "CREDENTIALS_UNAVAILABLE";
    static final String INTERRUPTED = "INTERRUPTED";

    private final AiProperties properties;
    private final AiInsightClient aiClient;
    private final AiReportStore store;
    private final DailyStatsRepository statsRepository;
    private final TargetQuerySampler querySampler;
    private final TargetProvider targetProvider;
    private final StringRedisTemplate redis;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int metricRetentionDays;
    private final ThreadPoolExecutor executor;

    @Autowired
    public AiReportService(AiProperties properties, AiInsightClient aiClient, AiReportStore store,
                           DailyStatsRepository statsRepository, TargetQuerySampler querySampler,
                           TargetProvider targetProvider, StringRedisTemplate redis,
                           AuditEventService auditEventService, ObjectMapper objectMapper,
                           @Value("${app.metrics.retention-days:30}") int metricRetentionDays) {
        this(properties, aiClient, store, statsRepository, querySampler, targetProvider, redis, auditEventService,
                objectMapper, metricRetentionDays, Clock.systemUTC());
    }

    AiReportService(AiProperties properties, AiInsightClient aiClient, AiReportStore store,
                    DailyStatsRepository statsRepository, TargetQuerySampler querySampler,
                    TargetProvider targetProvider, StringRedisTemplate redis,
                    AuditEventService auditEventService, ObjectMapper objectMapper,
                    int metricRetentionDays, Clock clock) {
        this.properties = properties;
        this.aiClient = aiClient;
        this.store = store;
        this.statsRepository = statsRepository;
        this.querySampler = querySampler;
        this.targetProvider = targetProvider;
        this.redis = redis;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.metricRetentionDays = metricRetentionDays;
        this.clock = clock;
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(properties.workerThreads(), properties.workerThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.workerQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-report-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 재시작 전에 끝나지 못한 PENDING은 다시 이어서 만들 수 없으므로 실패로 닫는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedReports() {
        int count = store.failAllPending(INTERRUPTED, "서버 재시작으로 생성이 중단되었습니다. 다시 요청해 주세요.", now());
        if (count > 0) log.info("Marked interrupted AI reports as FAILED. count={}", count);
    }

    public AiStatusResponse status() {
        return new AiStatusResponse(properties.generationAvailable(), properties.provider(), properties.model(),
                properties.zone().getId(), properties.generationAvailable() && properties.dailyReportScheduleEnabled(),
                properties.requestCooldownSeconds());
    }

    // ---------------------------------------------------------------- requests

    public AiReportResponse requestDailyReport(Long databaseConfigId, String date, Long actorId) {
        long id = ApiId.require(databaseConfigId, "id");
        TargetMetadata target = target(id);
        LocalDate reportDate = parseReportDate(date);
        Instant start = reportDate.atStartOfDay(properties.zone()).toInstant();
        Instant end = reportDate.plusDays(1).atStartOfDay(properties.zone()).toInstant();
        requireAvailable();
        if (!statsRepository.hasSamples(id, start, end)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_NO_DATA",
                    "해당 날짜에 수집된 메트릭이 없어 보고서를 만들 수 없습니다.");
        }
        enforceCooldown(AiReportType.DAILY_REPORT, id);
        long reportId = insertPending(AiReportType.DAILY_REPORT, target, reportDate, start, end,
                AiTriggerSource.MANUAL, actorId);
        submit(reportId, () -> generateDaily(reportId, target, reportDate, start, end));
        auditEventService.successCurrent(AuditAction.AI_REPORT_REQUESTED, AuditTargetType.DATABASE,
                Long.toString(id), id, "AI daily report requested for " + reportDate);
        return get(reportId);
    }

    public AiReportResponse requestQueryAnalysis(Long databaseConfigId, Long actorId) {
        long id = ApiId.require(databaseConfigId, "id");
        TargetMetadata target = target(id);
        requireAvailable();
        enforceCooldown(AiReportType.QUERY_ANALYSIS, id);
        long reportId = insertPending(AiReportType.QUERY_ANALYSIS, target, null, null, null,
                AiTriggerSource.MANUAL, actorId);
        submit(reportId, () -> generateQueryAnalysis(reportId, target));
        auditEventService.successCurrent(AuditAction.AI_REPORT_REQUESTED, AuditTargetType.DATABASE,
                Long.toString(id), id, "AI query analysis requested");
        return get(reportId);
    }

    /**
     * 스케줄러용. 활성 대상마다 해당 날짜 보고서가 없고 수집 표본이 있으면 생성을 예약한다.
     * @return 예약한 보고서 수
     */
    public int scheduleDailyReports(LocalDate reportDate) {
        if (!properties.generationAvailable()) return 0;
        Instant start = reportDate.atStartOfDay(properties.zone()).toInstant();
        Instant end = reportDate.plusDays(1).atStartOfDay(properties.zone()).toInstant();
        int scheduled = 0;
        for (TargetMetadata target : targetProvider.listEnabled()) {
            try {
                if (store.hasDailyReport(target.id(), reportDate)) continue;
                if (!statsRepository.hasSamples(target.id(), start, end)) continue;
                long reportId = store.insertPending(AiReportType.DAILY_REPORT, target.id(), target.name(),
                        reportDate, start, end, AiTriggerSource.SCHEDULED, null, aiClient.model(), now());
                submit(reportId, () -> generateDaily(reportId, target, reportDate, start, end));
                scheduled++;
            } catch (DuplicateKeyException e) {
                log.debug("Daily AI report already pending. databaseConfigId={}", target.id());
            } catch (ApiException e) {
                log.warn("Daily AI report not scheduled. databaseConfigId={}, code={}", target.id(), e.getCode());
            } catch (DataAccessException e) {
                log.warn("Daily AI report scheduling failed. databaseConfigId={}, exceptionType={}",
                        target.id(), e.getClass().getSimpleName());
            }
        }
        return scheduled;
    }

    public int deleteExpired() {
        return store.deleteRequestedBefore(now().minus(Duration.ofDays(properties.retentionDays())));
    }

    // ---------------------------------------------------------------- queries

    public AiReportResponse get(Long reportId) {
        long id = ApiId.require(reportId, "reportId");
        return store.find(id).map(this::toResponse).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "AI_REPORT_NOT_FOUND", "AI 보고서를 찾을 수 없습니다."));
    }

    public PageResponse<AiReportResponse> list(Long databaseConfigId, String type, String status, String date,
                                               Integer page, Integer size) {
        ApiId.validateOptional(databaseConfigId, "databaseConfigId");
        AiReportType reportType = parseEnum(AiReportType.class, type, "type");
        AiReportStatus reportStatus = parseEnum(AiReportStatus.class, status, "status");
        LocalDate reportDate = date == null || date.isBlank() ? null : parseDate(date);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageNumber > 10_000) throw invalid("page", "OUT_OF_RANGE", "page는 0~10000이어야 합니다.");
        if (pageSize < 1 || pageSize > 100) throw invalid("size", "OUT_OF_RANGE", "size는 1~100이어야 합니다.");
        long total = store.count(databaseConfigId, reportType, reportStatus, reportDate);
        List<AiReportResponse> items = store.page(databaseConfigId, reportType, reportStatus, reportDate,
                pageNumber, pageSize).stream().map(this::toResponse).toList();
        int totalPages = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new PageResponse<>(items, pageNumber, pageSize, total, totalPages);
    }

    // ---------------------------------------------------------------- generation

    void generateDaily(long reportId, TargetMetadata target, LocalDate reportDate, Instant start, Instant end) {
        run(reportId, () -> {
            DailyStats stats = statsRepository.load(target.id(), start, end, true);
            Instant previousStart = reportDate.minusDays(1).atStartOfDay(properties.zone()).toInstant();
            DailyStats previous = statsRepository.load(target.id(), previousStart, start, false);
            DailyStats previousOrNull = previous.sampleCount() == 0 ? null : previous;
            AiInsightClient.Result<DailyReportInsight> result = aiClient.dailyReport(
                    target.name(), reportDate, properties.zone().getId(), stats, previousOrNull);
            DailyReportInsight insight = result.value();
            DailyReport report = new DailyReport(reportDate, properties.zone().getId(), insight.summary(),
                    insight.overallStatus(), Math.max(0, Math.min(100, insight.healthScore())),
                    nonNull(insight.findings()), nonNull(insight.recommendations()), stats, previousOrNull);
            store.complete(reportId, json(report), result.inputTokens(), result.outputTokens(), result.model(), now());
        });
    }

    void generateQueryAnalysis(long reportId, TargetMetadata metadata) {
        run(reportId, () -> {
            CollectorTarget target;
            try {
                target = targetProvider.getForDiagnostic(metadata.id()).orElse(null);
            } catch (DatabaseCredentialUnavailableException e) {
                store.fail(reportId, CREDENTIALS_UNAVAILABLE, "대상 DB 계정 정보를 복호화하지 못했습니다.", now());
                return;
            }
            if (target == null) {
                store.fail(reportId, TARGET_UNREACHABLE, "대상 DB가 삭제되었습니다.", now());
                return;
            }
            TargetQuerySampler.Samples samples;
            try {
                samples = querySampler.sample(target, properties.maxQueriesPerAnalysis());
            } catch (SQLException | RuntimeException e) {
                log.warn("Query sampling failed. databaseConfigId={}, exceptionType={}", metadata.id(),
                        e.getClass().getSimpleName());
                store.fail(reportId, TARGET_UNREACHABLE, "대상 DB에서 쿼리 통계를 읽지 못했습니다.", now());
                return;
            }
            Instant collectedAt = now();
            if (samples.samples().isEmpty()) {
                QueryAnalysis empty = new QueryAnalysis(samples.source(), collectedAt,
                        "분석할 쿼리 표본이 없습니다. performance_schema가 꺼져 있고 지금 실행 중인 쿼리도 없습니다.",
                        QueryRiskLevel.LOW, List.of(),
                        List.of("누적 쿼리 통계를 보려면 대상 MariaDB에서 performance_schema=ON으로 재시작하고 "
                                + "모니터링 계정에 performance_schema SELECT 권한을 주세요."));
                store.complete(reportId, json(empty), 0, 0, null, now());
                return;
            }
            AiInsightClient.Result<QueryAnalysisInsight> result =
                    aiClient.queryAnalysis(metadata.name(), samples.source(), samples.samples());
            QueryAnalysis analysis = merge(samples, collectedAt, result.value());
            store.complete(reportId, json(analysis), result.inputTokens(), result.outputTokens(), result.model(),
                    now());
        });
    }

    /** 표본 순서를 기준으로 AI 판정을 붙이고 위험도 높은 순으로 정렬한다. AI가 지어낸 queryId는 버린다. */
    static QueryAnalysis merge(TargetQuerySampler.Samples samples, Instant collectedAt, QueryAnalysisInsight insight) {
        Map<String, QueryInsight> byId = nonNull(insight.queries()).stream()
                .filter(query -> query.queryId() != null)
                .collect(Collectors.toMap(QueryInsight::queryId, Function.identity(), (first, second) -> first));
        List<AnalyzedQuery> queries = new ArrayList<>();
        for (QuerySample sample : samples.samples()) {
            QueryInsight match = byId.get(sample.queryId());
            queries.add(match == null
                    ? new AnalyzedQuery(sample, null, null, null, null, null)
                    : new AnalyzedQuery(sample, match.riskLevel(), match.category(), match.problem(),
                    match.recommendation(), blankToNull(match.suggestedIndex())));
        }
        queries.sort(Comparator.comparing((AnalyzedQuery query) ->
                query.riskLevel() == null ? -1 : query.riskLevel().ordinal()).reversed());
        QueryRiskLevel overall = queries.stream().map(AnalyzedQuery::riskLevel).filter(level -> level != null)
                .max(Comparator.naturalOrder()).orElse(insight.overallRisk() == null ? QueryRiskLevel.LOW
                        : insight.overallRisk());
        return new QueryAnalysis(samples.source(), collectedAt, insight.summary(), overall, queries,
                nonNull(insight.generalRecommendations()));
    }

    private void run(long reportId, Runnable task) {
        try {
            task.run();
        } catch (AiGenerationException e) {
            log.warn("AI generation failed. reportId={}, code={}", reportId, e.code());
            store.fail(reportId, e.code(), e.getMessage(), now());
        } catch (RuntimeException e) {
            log.error("AI report generation crashed. reportId={}, exceptionType={}", reportId,
                    e.getClass().getSimpleName(), e);
            store.fail(reportId, INTERNAL_ERROR, "보고서 생성 중 내부 오류가 발생했습니다.", now());
        }
    }

    private void submit(long reportId, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            store.fail(reportId, "AI_BUSY", "AI 생성 대기열이 가득 찼습니다.", now());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_BUSY",
                    "AI 생성 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private long insertPending(AiReportType type, TargetMetadata target, LocalDate reportDate, Instant start,
                               Instant end, AiTriggerSource trigger, Long actorId) {
        try {
            return store.insertPending(type, target.id(), target.name(), reportDate, start, end, trigger, actorId,
                    aiClient.model(), now());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_REPORT_IN_PROGRESS",
                    "같은 대상의 AI 보고서가 이미 생성 중입니다.");
        }
    }

    private TargetMetadata target(long id) {
        return targetProvider.getMetadata(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "DATABASE_NOT_FOUND", "DB 설정을 찾을 수 없습니다."));
    }

    private void requireAvailable() {
        if (!properties.generationAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, AiGenerationException.UNAVAILABLE,
                    "AI 기능이 꺼져 있거나 API 키가 설정되지 않았습니다.");
        }
    }

    private void enforceCooldown(AiReportType type, long databaseConfigId) {
        String key = "rate-limit:ai:" + type.name().toLowerCase(Locale.ROOT) + ':' + databaseConfigId;
        try {
            Boolean accepted = redis.opsForValue().setIfAbsent(key, "1",
                    Duration.ofSeconds(properties.requestCooldownSeconds()));
            if (!Boolean.TRUE.equals(accepted)) {
                Long ttl = redis.getExpire(key);
                long retryAfter = ttl == null || ttl < 1 ? 1 : ttl;
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "같은 대상의 AI 요청은 " + properties.requestCooldownSeconds() + "초에 한 번만 할 수 있습니다.",
                        List.of(), Map.of("Retry-After", Long.toString(retryAfter)));
            }
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "AI 요청 제한 저장소를 사용할 수 없습니다.");
        }
    }

    private LocalDate parseReportDate(String value) {
        ZoneId zone = properties.zone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate date = value == null || value.isBlank() ? today.minusDays(1) : parseDate(value);
        if (!date.isBefore(today)) {
            throw invalid("date", "OUT_OF_RANGE", "date는 " + zone.getId() + " 기준 오늘 이전 날짜여야 합니다.");
        }
        if (date.isBefore(today.minusDays(metricRetentionDays))) {
            throw invalid("date", "OUT_OF_RANGE", "date는 최근 " + metricRetentionDays + "일 이내여야 합니다.");
        }
        return date;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid("date", "INVALID_FORMAT", "date는 YYYY-MM-DD 형식이어야 합니다.");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "INVALID_VALUE", field + " 값이 올바르지 않습니다.");
        }
    }

    private static ApiException invalid(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }

    private AiReportResponse toResponse(AiReportRow row) {
        DailyReport daily = null;
        QueryAnalysis query = null;
        if (row.status() == AiReportStatus.SUCCEEDED && row.contentJson() != null) {
            try {
                if (row.type() == AiReportType.DAILY_REPORT) {
                    daily = objectMapper.readValue(row.contentJson(), DailyReport.class);
                } else {
                    query = objectMapper.readValue(row.contentJson(), QueryAnalysis.class);
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored AI report content is unreadable. id=" + row.id(), e);
            }
        }
        return new AiReportResponse(row.id(), row.type(), row.databaseConfigId(), row.databaseName(),
                row.reportDate(), row.windowStart(), row.windowEnd(), row.status(), row.triggerSource(), row.model(),
                row.requestedAt(), row.completedAt(), row.errorCode(), row.errorMessage(), row.inputTokens(),
                row.outputTokens(), daily, query);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI report serialization failed", e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
