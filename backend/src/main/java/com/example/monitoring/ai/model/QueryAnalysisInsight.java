package com.example.monitoring.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Claude가 구조화 출력으로 돌려주는 위험 쿼리 분석 본문. */
public record QueryAnalysisInsight(
        @JsonPropertyDescription("Korean overview in 2-4 sentences") String summary,
        @JsonPropertyDescription("Highest risk level across all queries") QueryRiskLevel overallRisk,
        @JsonPropertyDescription("One assessment per sample, every queryId exactly once, most risky first")
        List<QueryInsight> queries,
        @JsonPropertyDescription("Korean server-wide recommendations about configuration, schema or monitoring")
        List<String> generalRecommendations
) {
}
