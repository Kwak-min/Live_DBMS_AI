package com.example.monitoring.ai.model;

import java.time.Instant;
import java.util.List;

/** 조회 응답의 queryAnalysis. 표본이 0건이면 AI를 호출하지 않고 overallRisk=LOW, queries=[]로 끝낸다. */
public record QueryAnalysis(
        QuerySampleSource source,
        Instant collectedAt,
        String summary,
        QueryRiskLevel overallRisk,
        List<AnalyzedQuery> queries,
        List<String> generalRecommendations
) {
}
