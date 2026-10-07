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

## 2026-10-08 FCM 수정과 #28 통합 검증

STOMP는 #28의 기존 계약 복구를 그대로 사용한다. FCM 수정 커밋은 `0ac113e5130798904059a8c28988adcd40b94c46`이며 기준 develop은 `0a07ac6dc072d6eda63f6ed7173b7060b1debe31`(#28 병합)이다. `/fcm/send/`를 `/wp/`로 바꾸는 정확한 FCM 호스트·경로 처리와 회귀 테스트만 변경했고 URI 동일성 검사, endpoint 허용 정책, DNS pinning, TLS 검증을 유지했다. 프론트는 브라우저가 제공한 구독 endpoint를 그대로 보내면 된다.

프론트 전달 기준은 [Part C 프론트 계약](frontend-c-contract.md)이다. 토픽·envelope·heartbeat·재연결/REST 대조·Push API·클릭 경로를 한 곳에 정리했다. 상세 필드와 예제는 기존 API/events/contract-examples가 기준이다.

### 이번 코드 검증

[실행 요약 JSON](evidence/part-c-fcm/2026-10-08/summary.json)에 기준 SHA·backend tree·전후 소스 해시·고유 실행 수·정리 결과를 기록했다.

| 실행 | 결과 |
| --- | --- |
| FCM 회귀 | 수정 전 4개 중 Chrome 경로 1개 실패, 수정 후 4개 모두 통과; AES128GCM 복호화·VAPID 서명·Mozilla 유지·예상 밖 URI 변형 거부 |
| 기본 전체 테스트 | 547개 발견, 503개 실행 통과, 조건부 44개는 아래 별도 실행 |
| 실제 Redis 소비·보관 | 27개 통과 |
| 실제 Redis 중단/재시작 | 1개 통과; 61초 중단 중 잘못된 STALE 0건, 실제 수집 중단 사건과 지속 성공 후 복구 확인 |
| 실제 HTTP/WebSocket/STOMP | 11개 통과; Bearer CONNECT·토픽 수신, query token/잘못된 Origin/SEND 거절, 토큰 만료·HTTP 로그아웃 종료, 재시작/dedup/ACK/DLQ |
| C 파이프라인·제공자 TLS | 5개 통과; 위험도→사건/상태→STOMP, 재시작/세션 폐기/취소, Web Push 암호화/VAPID, Slack 성공/재시도/간격/오류 |
| build·bootstrap | bootJar 성공, 최초 관리자 생성 성공, 기존 활성 관리자에서 재생성 거절 및 DB 상태 유지 |

총 **547개 고유 테스트 실행, 실패 0·오류 0·미실행 0**이다. 조건부 검증은 전용 PostgreSQL 16·Redis 7.4.11과 실제 서버/프로토콜을 사용했다. 알림 제공자 시험은 로컬 TLS fixture이며 실제 Slack/FCM 재발송은 0건이다. C 파이프라인 입력은 정규 metric_data 행·Redis payload이고 MariaDB 수집기를 다시 실행한 결과로 표시하지 않는다.

최초 Windows PostgreSQL initdb는 한글 경로 인코딩 문제로 실패했다. 동일 소스의 통과한 기본/Redis/bootJar 결과를 보존하고 ASCII 경로의 시험 환경에서 나머지를 실행했다. 기존 QA 스크립트 후처리에서 최종 manifest가 생성되지 않은 문제와 제품 테스트 결과를 구분하여, JUnit suite 헤더·각 명령 종료 코드·별도 고유 test ID 목록·전후 소스 해시를 독립 대조한 JSON을 전달한다. 전용 앱·DB·Redis 종료와 8개 시험 포트 해제를 확인했다.

### 기존 실제 Slack·Chrome 수신 기록

| 채널 | 실행 기준 | 실제 수신 증거 | 범위 |
| --- | --- | --- | --- |
| Slack | `b06df621f3cbf039c4cf246588fc2f13333bbd6a` | 2026-10-07 23:00 KST 발생·복구 HTTP 200/본문 `ok`, SENT 각 1회, 사용자 채널 화면 확인 | production 발송 경로, 가상 사건 2건 |
| Windows Chrome Push | `41db273bc81f32b755e487005ebcee47836db6f3` + 이번에 커밋한 로컬 FCM 수정 | 2026-10-08 00:13:58/00:14:20 KST FCM 201, SENT 각 1회, SW 수신·사람 확인; 00:14:35 클릭 | 임시 QA 페이지/SW, 가상 사건 2건 |

Slack 사건은 `fd04dd44-fa85-4b48-a34c-0c08ce43a303`, Push 사건은 `ab0a20fb-0d8b-4652-8526-96d79d99150e`였다. 모두 TEST ONLY다. 이전 실제 수신을 이번 최종 커밋에서 새로 실행한 것으로 표시하지 않는다. 당시 Push patch SHA256은 `4f74ad2f8615edf38d81393abb1098ab4af30d8418172b945af44c3131639344`이며 원래 로컬 소스 두 파일을 이번 FCM 커밋에 그대로 반영했다.

로컬 원본 기록은 Slack `part-c-stream-retention/.omo/evidence/slack-external-20261007/{provider-final.jsonl,human-confirmation.json,result.md}`, Push 루트 `.omo/evidence/part-c-followup-20261007/{push-results.jsonl,result.md}`에 보존했다. 구독 주소·Webhook·인증 토큰·VAPID private key는 전달 문서에 포함하지 않는다.

### 완료 경계와 담당별 인계

- C: FCM 수정 커밋·PR 준비, #28 조합 테스트/실행 검증, 프론트 연동 계약과 수신 증거 정리. 병합·배포 완료를 뜻하지 않는다.
- A: 전체 통합, 공유 PostgreSQL/Redis와 단일 `PUBLIC_ORIGIN` 환경 준비, 기능 flag/VAPID/허용 호스트 주입, 배포 조율과 PR 병합 판단.
- B와 C: 인증·로그아웃·세션 폐기 시 STOMP 종료의 백엔드 실행 결과를 공유하고, 공유 환경에서 다중 탭/Refresh 재사용/역할 변경/비활성화를 함께 확인한다. 자동 재연결은 폐기된 세션으로 반복하지 않는다.
- 프론트와 C: 제품 권한 UI·제품 SW에서 발생/복구 알림 표시, 기존 창/새 창 이동, `/incidents/{id}` 상세 렌더링, 로그인 후 사건 경로 복귀를 검수한다. 모바일·다른 브라우저는 별도 검수한다.

A/B의 `RISK_ENABLED=true` C 상태 조회 전환과 A 임시 display 갱신 중단은 기존 2026-10-08 00:11 KST 실행 기록에서 이미 확인했다. 이번 작업에서 재구현하지 않았다. 제품 화면과 공유 배포 검수는 해당 담당자가 이어간다.

검증된 preparer bytecode SHA256은 `6e15b70cbc0ab9512d8f7d556628fc5b2cea761482d2e89c3787d59683e0cd1b`로, 기존 Chrome 실제 수신 실행 기록의 preparer와 일치한다. 이 일치는 FCM 수정에 대한 증거이며 전체 앱이나 제품 SW가 동일하다는 뜻은 아니다.
