package com.example.monitoring.ai.model;

/**
 * 대상 DB에서 읽은 쿼리 표본 1건. queryText는 리터럴이 ?로 바뀐 문장이다.
 * PERFORMANCE_SCHEMA 표본은 누적 통계를, PROCESSLIST 표본은 지금 실행 중인 1건의 경과 시간(runningSeconds)만 가진다.
 */
public record QuerySample(
        String queryId,
        String schemaName,
        String queryText,
        Long executions,
        Double totalLatencyMs,
        Double avgLatencyMs,
        Double maxLatencyMs,
        Long rowsExamined,
        Long rowsSent,
        Long rowsAffected,
        Long noIndexUsedCount,
        Long noGoodIndexUsedCount,
        Long tmpDiskTables,
        Long sortMergePasses,
        Long runningSeconds
) {
}
