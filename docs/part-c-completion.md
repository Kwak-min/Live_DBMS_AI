# Part C 완료 범위와 최종 연동

## 반영된 백엔드

- PR #15: V4·기존 대상 초기화, Lifecycle 실제 구현과 B 필수 의존성, 인증 STOMP.
- PR #18: cg:risk 메트릭·Heartbeat 소비, 상태·위험도·사건 생성/복구와 API, Web Push·Slack 구독·발송·재시도·취소·세션 폐기 연동, V5 성공 수신 기록.
- PR #19: Redis 장애 중 PostgreSQL의 실제 수집 시각을 확인해 COLLECTION_STALE 오탐 방지, 공통 메트릭 스트림 설정 통일.
- A의 [T16 실제 환경 재검증](part-a-integration-results.md#t16-재검증-c-pr-19-2026-10-06)은 PR #20에 반영됐다. Redis 65초 중단 중 오탐 0건, 실제 수집기 중단 후 30초에 사건 생성, 수집 재개 후 정상 복구를 확인했다.

상세 계약: [위험도·알림 인계](part-c-risk-notifications.md), [API](api.md), [이벤트](events.md).

## Redis 스트림 안전 보관 정리

서블릿 백엔드에서 기본 활성화되고 시작 후 60초부터 매 60초 간격으로 실행한다.
`APP_REDIS_STREAM_RETENTION_ENABLED=false`로 중지하거나
`APP_REDIS_STREAM_RETENTION_INTERVAL_MS`로 간격을 조정할 수 있다.
기존 `app.redis.*-stream-key` 설정을 그대로 사용한다.

| 스트림 기본값 | 정리 전 반드시 존재해야 하는 그룹 |
| --- | --- |
| stream:metrics | cg:risk, cg:realtime |
| stream:collector-heartbeats | cg:risk |
| stream:statuses | cg:realtime |
| stream:incidents | cg:realtime, cg:notification |

Redis 서버 시각과 스트림 ID의 생성 시각을 기준으로 최소 24시간을 보관한다.
추가로 존재하는 그룹까지 포함해 모든 그룹의 `last-delivered-id`와 가장 오래된
pending ID를 확인한다. 이 경계 기록과 이후 기록은 삭제하지 않는다.
필수 그룹이 없거나 아직 읽지 않은 그룹이 있으면 24시간 경계로는 삭제하지 않는다.

대신 최대 보관 상한(`APP_REDIS_STREAM_RETENTION_MAX_AGE_HOURS`, 기본 168시간=7일)을 둔다.
이보다 오래된 기록은 필수 그룹이 없거나(해당 기능 비활성) 그룹이 멈춰 있어도 삭제한다.
단, 어느 그룹에서든 pending인 기록과 그 이후 기록은 상한이 지나도 삭제하지 않는다.
따라서 기능을 나중에 켜서 그룹이 `0-0`부터 생성되면 최근 7일 이내 기록만 다시 처리한다.
상한을 `0`으로 두면 이전처럼 그룹 경계만 따르며, 0이 아닌 값은 24 이상이어야 한다.
pending이 오래 남은 그룹은 상한으로도 정리되지 않으므로 backlog·pending과 Redis 메모리를 함께 점검한다.

기능을 영구히 끈 경우 남은 그룹은 pending을 계속 붙잡을 수 있다. 다시 켤 계획이 없으면
해당 그룹을 지운다(예: `XGROUP DESTROY stream:metrics cg:realtime`). 다시 켜면 그룹은
자동으로 `0-0`부터 다시 만들어진다.

그룹 확인·pending 확인·배치 선정·정확한 MINID trim은 한 Lua 실행 안에서 처리한다.
한 번에 스트림별 최대 1,000건을 삭제한다. 실패한 스트림은 경고를 남기고
다른 스트림 정리를 계속하며 다음 주기에 다시 시도한다. 처리 위치 경계를
보존하므로 모두 오래된 기록만 있는 스트림에서도 마지막 경계 기록이 남을 수 있다.
키 전체 삭제, TTL, XADD MAXLEN은 사용하지 않는다.

DLQ는 자동 삭제 대상이 아니다. [운영 규격](integration-operations.md)의 7일 보관 및
PostgreSQL/export 기록 확인 절차를 따른다. V1~V5, A의 상태 표시 갱신,
B의 기존 상태 조회, 기존 소비/ACK/DLQ 처리 계약은 변경하지 않는다.

검증 위치: `RedisStreamRetentionIntegrationTest`(실제 Redis),
`RedisStreamRetentionContextTest`(기본 활성화·비활성화·무웹 bootstrap).
기존 Redis 통합 테스트와 동일한 `partc.stream.integration=true`,
`partc.stream.redis.port` 시스템 속성을 Gradle Test JVM에 전달한다.
검증은 테스트 전용 Redis 인스턴스에서 수행한다.

## 최종 적용·수신 검증

- [ ] 후속 보관 정리 PR의 팀 리뷰와 병합. 이 문서는 병합·공유 배포 승인이 아니다.
- [ ] 환경별 RISK_ENABLED·REALTIME_ENABLED·NOTIFICATIONS_ENABLED 설정 확인. 세 기능은 기본 false이며, 보관 정리 활성화로 소비 기능이 켜지지 않는다.
- [ ] 프론트 서비스 워커·권한 UI·개인 Push 구독 연동 후 실제 브라우저/기기에서 개시·복구 수신과 동일 origin 사건 이동 검증(T23).
- [ ] 지정된 Slack 테스트 수신처에서 실제 개시·복구 수신 검증. 재시도·취소·암호화·세션 폐기는 백엔드 검증과 구분해 기록한다(T24~T25).
- [ ] A/B와 상태 표시 전환 시점 합의: C 상태 갱신 검증 후 B 조회를 monitoring_states로 전환하고 A 임시 표시 갱신을 제거한다. 합의 전에는 현재 경로를 유지한다.

Web Push VAPID·허용 호스트·Slack 수신처는 대상 환경에서 설정하고 비밀값을
PR/로그/톡방에 붙이지 않는다. 프론트의 서비스 워커·권한·실기기 수신 검수는
프론트 담당 범위이며, C는 구독 API·발송 및 실제 연동 확인을 지원한다.
외부 Push/Slack 제공자 및 모바일 수신은 검증 결과가 생기기 전까지 완료로 표시하지 않는다.
