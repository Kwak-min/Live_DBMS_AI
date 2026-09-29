package com.example.monitoring.domain;

/** 수집 실패 원인. 접속 오류와 필수 조회 SQL 오류를 구분한다. */
public enum MetricErrorCode {
    AUTH_FAILED,
    CONNECT_TIMEOUT,
    CONNECTION_REFUSED,
    QUERY_FAILED,
    INTERNAL_ERROR,
    UNKNOWN
}
