package com.example.monitoring.auth.service;

import java.util.Set;
import java.util.UUID;

/**
 * 로그인 세션이 폐기되었음을 알린다. 트랜잭션 커밋 뒤 실시간(STOMP) 연결을 10초 재검증 주기를 기다리지 않고
 * 바로 끊는 데 쓴다(integration-security.md 5절).
 *
 * @param sessionIds 폐기된 세션(sid). 사용자 단위 폐기면 비어 있을 수 있다
 * @param userId 역할·활성 변경처럼 사용자의 모든 세션을 폐기했으면 그 사용자 ID, 아니면 null
 */
public record AuthSessionsRevokedEvent(Set<UUID> sessionIds, Long userId) {

    public static AuthSessionsRevokedEvent session(UUID sessionId) {
        return new AuthSessionsRevokedEvent(Set.of(sessionId), null);
    }

    public static AuthSessionsRevokedEvent user(long userId) {
        return new AuthSessionsRevokedEvent(Set.of(), userId);
    }
}
