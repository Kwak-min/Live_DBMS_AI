package com.example.monitoring.realtime.stomp;

import com.example.monitoring.auth.service.AuthSessionsRevokedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 로그아웃·Refresh 재사용·역할/활성 변경으로 세션이 폐기되면 커밋 직후 해당 STOMP 연결을 닫는다.
 * 10초 주기 재검증만으로는 폐기 후 최대 10초 동안 실시간 이벤트를 계속 받을 수 있었다.
 */
@Component
@ConditionalOnProperty(prefix = "monitoring.realtime", name = "enabled", havingValue = "true")
public class StompSessionRevocationListener {

    private final StompSessionRegistry registry;

    public StompSessionRevocationListener(StompSessionRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRevoked(AuthSessionsRevokedEvent event) {
        event.sessionIds().forEach(registry::closeAuthenticationSession);
        if (event.userId() != null) {
            registry.closeUserSessions(event.userId());
        }
    }
}
