package com.example.monitoring.ai.service;

import com.example.monitoring.ai.collect.TargetQuerySampler;
import com.example.monitoring.ai.model.QueryAnalysis;
import com.example.monitoring.ai.model.QueryAnalysisInsight;
import com.example.monitoring.ai.model.QueryInsight;
import com.example.monitoring.ai.model.QueryRiskLevel;
import com.example.monitoring.ai.model.QuerySample;
import com.example.monitoring.ai.model.QuerySampleSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiReportServiceMergeTest {

    @Test
    void attachesInsightsByQueryIdSortsByRiskAndDropsInventedIds() {
        TargetQuerySampler.Samples samples = new TargetQuerySampler.Samples(QuerySampleSource.PERFORMANCE_SCHEMA,
                List.of(sample("Q1", "SELECT * FROM a WHERE x = ?"),
                        sample("Q2", "SELECT * FROM b"),
                        sample("Q3", "UPDATE c SET y = ?")));
        QueryAnalysisInsight insight = new QueryAnalysisInsight("요약", QueryRiskLevel.LOW, List.of(
                new QueryInsight("Q1", QueryRiskLevel.LOW, "OK", "문제 없음", "유지", ""),
                new QueryInsight("Q2", QueryRiskLevel.CRITICAL, "FULL_SCAN", "전체 스캔", "조건 추가",
                        "CREATE INDEX idx_b ON b (x)"),
                new QueryInsight("Q9", QueryRiskLevel.HIGH, "MISSING_INDEX", "없는 쿼리", "무시", "")),
                List.of("일반 권고"));

        QueryAnalysis merged = AiReportService.merge(samples, Instant.parse("2026-10-07T00:00:00Z"), insight);

        assertThat(merged.queries()).extracting(query -> query.sample().queryId())
                .containsExactly("Q2", "Q1", "Q3");
        assertThat(merged.queries().get(0).suggestedIndex()).isEqualTo("CREATE INDEX idx_b ON b (x)");
        assertThat(merged.queries().get(1).suggestedIndex()).isNull();
        assertThat(merged.queries().get(2).riskLevel()).isNull();
        assertThat(merged.overallRisk()).isEqualTo(QueryRiskLevel.CRITICAL);
        assertThat(merged.generalRecommendations()).containsExactly("일반 권고");
    }

    private static QuerySample sample(String id, String text) {
        return new QuerySample(id, "app", text, 10L, 100.0, 10.0, 20.0, 1000L, 10L, 0L, 10L, 0L, 0L, 0L, null);
    }
}
