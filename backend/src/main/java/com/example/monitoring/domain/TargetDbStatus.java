package com.example.monitoring.domain;

public enum TargetDbStatus {
    UP,
    DOWN,
    UNKNOWN,
    /** 위험 감지(FATAL 인시던트) 또는 수동 조작으로 인해 접근이 차단된 상태 */
    BLOCKED
}
