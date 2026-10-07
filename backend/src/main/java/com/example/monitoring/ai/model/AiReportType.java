package com.example.monitoring.ai.model;

public enum AiReportType {
    /** 하루치 메트릭·사건을 요약한 DB 상태 보고서. */
    DAILY_REPORT,
    /** 대상 DB의 상위 쿼리 통계를 보고 위험 쿼리를 골라낸 분석. */
    QUERY_ANALYSIS
}
