package com.example.monitoring.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Claude가 구조화 출력으로 돌려주는 일일 보고서 본문. */
public record DailyReportInsight(
        @JsonPropertyDescription("Korean summary of the day in 3-5 sentences, citing key numbers") String summary,
        @JsonPropertyDescription("HEALTHY, WARNING or CRITICAL for the whole day") OverallHealth overallStatus,
        @JsonPropertyDescription("Integer health score from 0 (down all day) to 100 (no issues)") int healthScore,
        @JsonPropertyDescription("Notable findings ordered by severity, most severe first; empty when nothing stands out")
        List<AiFinding> findings,
        @JsonPropertyDescription("Concrete Korean action items for the DBA, most important first")
        List<String> recommendations
) {
}
