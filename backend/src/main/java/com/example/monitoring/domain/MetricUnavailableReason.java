package com.example.monitoring.domain;

/** nullable 지표가 null인 이유. 실제 0은 누락이 아니므로 사용하지 않는다. */
public enum MetricUnavailableReason {
    UNSUPPORTED,
    WARMUP,
    COUNTER_RESET,
    COLLECTION_FAILED,
    QUERY_FAILED
}
