package com.example.monitoring.ai.llm;

import com.example.monitoring.ai.model.DailyReportInsight;
import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.QueryAnalysisInsight;
import com.example.monitoring.ai.model.QuerySample;
import com.example.monitoring.ai.model.QuerySampleSource;

import java.time.LocalDate;
import java.util.List;

/**
 * LLM 경계. 운영 구현은 {@link ClaudeAiInsightClient}이고 테스트는 가짜 구현으로 바꾼다.
 * 실패는 {@link AiGenerationException}으로만 던진다.
 */
public interface AiInsightClient {

    Result<DailyReportInsight> dailyReport(String databaseName, LocalDate reportDate, String timeZone,
                                           DailyStats stats, DailyStats previousDayStats);

    Result<QueryAnalysisInsight> queryAnalysis(String databaseName, QuerySampleSource source,
                                               List<QuerySample> samples);

    /** 생성에 쓰는 모델 ID. ai_reports.model에 기록한다. */
    String model();

    record Result<T>(T value, long inputTokens, long outputTokens) {
    }
}
