package com.example.monitoring.ai.model;

import java.time.Instant;

/** 보고 구간의 1시간 단위 집계. 값이 없는 지표는 null이다(0으로 채우지 않는다). */
public record HourlyStat(
        Instant hourStart,
        long samples,
        long failedSamples,
        Double avgConnectionUsagePercent,
        Double avgQps,
        Long slowQueries,
        Double avgResponseTimeMs
) {
}
