package com.example.monitoring.ai.model;

/**
 * 쿼리 표본 출처. PERFORMANCE_SCHEMA는 MariaDB가 리터럴을 ?로 바꾼 정규화 문장(digest)이고,
 * PROCESSLIST는 performance_schema가 꺼진 서버에서 지금 실행 중인 문장을 백엔드가 리터럴을 지워 보낸 것이다.
 */
public enum QuerySampleSource { PERFORMANCE_SCHEMA, PROCESSLIST }
