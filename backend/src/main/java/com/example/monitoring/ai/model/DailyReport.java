package com.example.monitoring.ai.model;

import java.time.LocalDate;
import java.util.List;

/** 조회 응답의 dailyReport. AI 해석과 그 근거가 된 집계를 함께 준다. */
public record DailyReport(
        LocalDate reportDate,
        String timeZone,
        String summary,
        OverallHealth overallStatus,
        int healthScore,
        List<AiFinding> findings,
        List<String> recommendations,
        DailyStats stats,
        DailyStats previousDayStats
) {
}
