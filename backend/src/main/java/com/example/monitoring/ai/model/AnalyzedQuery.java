package com.example.monitoring.ai.model;

/** 표본 통계와 AI 판정을 합친 한 행. AI가 판정을 빠뜨린 표본은 riskLevel 이하가 null이다. */
public record AnalyzedQuery(
        QuerySample sample,
        QueryRiskLevel riskLevel,
        String category,
        String problem,
        String recommendation,
        String suggestedIndex
) {
}
