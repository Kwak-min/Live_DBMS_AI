# Redis·STOMP·알림 규격 초안 v0.2

**팀 배포용 v1 개발 기준**. [REST](api.md) / [전체 기준](integration-contract-draft.md) / [운영](integration-operations.md) / [보안](integration-security.md). JSON은 설명용 예시이며 실제 캡처가 아니다. 현재 발행 코드의 형태는 마지막 절에 따로 기록한다.

Part C의 정상·실패·사건·알림 예제는 [권위 있는 Part C fixture](contract-examples/part-c.json)에서 바로 사용할 수 있다.

## 1. 지표 사전

| 필드 | 타입·단위 | 정의 |
| --- | --- | --- |
| cpuUsage / memoryUsage | number?, 0~100% | 현재 수집원 없음. null·UNSUPPORTED, 위험 판단 제외 |
| activeConnections | integer? 개 | Threads_connected, 활성 쿼리 수와 다름 |
| maxConnections | integer? 개 | max_connections; 0/null이면 비율 불가 |
| threadsRunning | integer? 개 | Threads_running |
| qps | number? 회/초 | 두 유효 관측의 Queries 증가량 / 실제 경과 초 |
| slowQueries | integer? 개 | MariaDB Slow_queries 누적 카운터, 초기화/재시작 시 감소 가능 |
| slowQueriesDelta | integer? 개 | 같은 구간의 Slow_queries 증가량 |
| slowQueriesPerSecond | number? 개/초 | slowQueriesDelta / metricWindowSeconds; 위험도 입력 |
| metricWindowSeconds | number? 초 | 파생 지표가 공통으로 사용한 실제 관측 간격, 유효하면 >0 |
| storageBytes | integer? byte | 계정이 볼 수 있는 모든 information_schema.TABLES의 data_length+index_length 합. 지정 스키마 하나나 파일시스템 사용량이 아님 |
| responseTimeMs | integer? ms | 수집 시 JDBC 연결 성립까지, 접속 실패면 실패까지 시간. Ping API는 연결+SELECT 1+VERSION 전체 시간 |
| timestamp | Time | 수집 시작 시각 |
| collectionAttemptTime | Time | 실제 시작 시각, MVP에서는 timestamp와 같은 값 |
| lastSuccessAt | Time? | 같은 configVersion에서 이 스냅샷까지 마지막 SUCCESS의 관측 시각 |

모든 numeric은 유한한 비음수이며 integer는 JS 안전 정수 상한 이내다. Uptime과 Queries 원본은 A가 비교용 내부 상태로 유지한다. A 재시작 시 같은 configVersion의 최신 저장 메트릭에서 lastSuccessAt을 복원하며 원본 카운터 기준은 초기화하여 WARMUP부터 시작한다. 카운터 감소/Uptime 감소/중간 실패/설정 버전 변경이면 기준을 초기화한다. 첫 유효 관측은 qps·slowQueriesDelta·slowQueriesPerSecond·metricWindowSeconds가 모두 null이다. 기준이 같고 Queries/Slow_queries 모두 유효한 다음 관측부터 실제 경과 시간으로 계산한다. 0으로 보충하지 않는다.

필수 원본은 Threads_connected/max_connections/Threads_running/Queries/Slow_queries/Uptime. 필수 원본 일부 실패는 PARTIAL_FAILURE, 연결 자체 실패는 CONNECTION_FAILED, 필수 원본 모두 성공은 SUCCESS다. 첫 파생 지표 WARMUP 또는 선택 지표 storageBytes 조회 실패만으로 SUCCESS를 실패로 바꾸지 않는다. 필수 조회 SQL 오류와 접속 오류는 구분한다. 저장된 접속 정보를 복호화하지 못해 접속을 시도하지 못한 경우도 대상에 접속할 수 없으므로 CONNECTION_FAILED이며, errorCode=INTERNAL_ERROR·responseTimeMs=0으로 대상 장애(AUTH_FAILED/CONNECT_TIMEOUT/CONNECTION_REFUSED)와 구분한다. C는 이를 다른 접속 실패와 같게 connectionStatus=DOWN과 CONNECTION_FAILURE 판정에 사용한다.

`errorCode`는 null 또는 AUTH_FAILED/CONNECT_TIMEOUT/CONNECTION_REFUSED/QUERY_FAILED/INTERNAL_ERROR/UNKNOWN. `errorMessage`는 안전한 짧은 설명 또는 null. `unavailableMetrics`는 nullable 지표명→UNSUPPORTED/WARMUP/COUNTER_RESET/COLLECTION_FAILED/QUERY_FAILED 맵이다. 실제 0은 누락 목록에 넣지 않는다. PARTIAL_FAILURE여도 성공한 필드는 유지한다. 접속 실패 시 실패까지 responseTimeMs는 유지할 수 있다.

C의 파생 activeConnectionsRatio=activeConnections/maxConnections는 0 이상인 원래 비율이며 1 초과도 허용하고 자르지 않는다. maxConnections가 0/null이거나 분자가 null이면 판단 불가다. 정책 임계치는 0~1 범위다. 사용량 백분율과 단위를 혼용하지 않는다. 모든 필수 원본 성공 여부와 각 규칙 입력의 유효 여부를 따로 검사하여 null을 정상으로 판정하지 않는다.

## 2. 내부 이벤트 공통 형식

Redis Stream의 hash 필드는 정확히 `payload` 하나이며 값은 UTF-8 JSON 문자열이다. 한 번 파싱하면 객체가 된다. 모든 내부 이벤트는 아래 공통 필드를 가진다.

| 필드 | 타입·규칙 |
| --- | --- |
| schemaVersion | integer, 1 |
| eventId | UUID, 최초 생성 시 발급; 같은 논리 이벤트 재발행에는 유지 |
| eventType | 아래 고정 목록 |
| publishedAt | UTC Time, 최초 outbox/이벤트 생성 시각; 재시도 시 유지 |

허용 타입은 MetricCollectedEvent, MonitoringStatusChangedEvent, IncidentCreatedEvent, IncidentUpdatedEvent, IncidentResolvedEvent, CollectorHeartbeatEvent다. 정의된 필드는 모두 전송하고 nullable만 null로 둔다. consumer는 미지의 선택 필드는 무시하지만 모르는 버전/타입·필수 누락·타입/enum 오류는 DLQ 처리한다. payload 최대 64KiB.

For `IncidentUpdatedEvent`, the producer must persist the internal
`severityTransition` value in the same incident/outbox transaction. The field
is required and accepts only `INCREASED` or `DECREASED`. `IncidentCreatedEvent`
and `IncidentResolvedEvent` omit it. The notification parser rejects a missing,
unknown, or misplaced value; the realtime adapter strips it before broadcasting
the public Incident shape. REST and STOMP clients therefore never infer a
transition from severity history.

### MetricCollectedEvent — A 생산

REST Metric의 전체 필드를 포함하되 `id`만 `metricId`로 매핑하고 `databaseName`(표시명)을 추가한다. host/port/username/password는 제외한다. metricId는 저장된 스냅샷과 동일하다.

```json
{
  "schemaVersion":1,
  "eventId":"b43a63cb-5f28-4f4a-8de1-412c352ba79d",
  "eventType":"MetricCollectedEvent",
  "publishedAt":"2026-09-28T03:00:00.050Z",
  "metricId":501,
  "databaseConfigId":12,
  "configVersion":2,
  "databaseName":"운영 MariaDB",
  "timestamp":"2026-09-28T03:00:00.000Z",
  "collectionAttemptTime":"2026-09-28T03:00:00.000Z",
  "lastSuccessAt":"2026-09-28T03:00:00.000Z",
  "cpuUsage":null,
  "memoryUsage":null,
  "activeConnections":18,
  "maxConnections":151,
  "qps":24.2,
  "slowQueries":310,
  "slowQueriesDelta":2,
  "slowQueriesPerSecond":0.4,
  "metricWindowSeconds":5.0,
  "threadsRunning":3,
  "storageBytes":104857600,
  "responseTimeMs":12,
  "collectionStatus":"SUCCESS",
  "errorCode":null,
  "errorMessage":null,
  "unavailableMetrics":{"cpuUsage":"UNSUPPORTED","memoryUsage":"UNSUPPORTED"}
}
```

실패해도 같은 구조를 발행한다. credential을 읽을 수 없거나 복호화할 수
없는 경우 `collectionStatus=CONNECTION_FAILED`, `errorCode=INTERNAL_ERROR`,
`responseTimeMs=0`으로 기록하며 공개 connectionStatus는 `DOWN`, 원인은
`CONNECTION_FAILURE`로 매핑한다. 연결을 시작한 뒤 실패한 경우에는 실패까지
측정한 responseTimeMs를 유지하되 같은 `CONNECTION_FAILED`/
`CONNECTION_FAILURE` 의미를 사용한다. `PARTIAL_FAILURE`는 연결된 수집에서
일부 필수 query만 사용할 수 없을 때만 사용한다. errorCode와 실패 지표의
unavailableMetrics는 반드시 채운다. 수집 실패를 메시지 미발행으로 표현하지
않는다. PostgreSQL 자체 장애로 저장하지 못한 경우만 발행을 보류하고 별도
운영 장애로 기록한다.

### MonitoringStatusChangedEvent — C 생산

공통 필드 + API StatusSnapshot의 전체 필드(평면 구조). `deleted:boolean`을 포함한다. 삭제 이벤트도 마지막 stateVersion과 deleted=true, enabled=false, PAUSED, riskLevel=null, openIncidentIds=[]로 발행한다. 삭제 후 GET status는 404다. 변경·신규 수집·시각 경과로 신선도가 변하면 상태 저장과 outbox를 한 트랜잭션으로 기록한다.

### IncidentCreated/Updated/ResolvedEvent — C 생산

공통 필드 + API Incident 전체 필드 + `timestamp:Time`(이번 상태 전이 시각), `sourceEventId:UUID?`. Metric에서 유래하면 sourceEventId와 sourceMetricId를 채우고 타이머/관리 변경은 null을 허용한다. 상태 전이마다 eventId를 새로 만들지만 재전송에는 유지한다. incidentId는 한 사건 동안 고정, incidentVersion은 상태/심각도 변경마다 증가한다. 매 관측의 lastObservedAt 갱신만으로 버전 증가·사건 이벤트 발행은 하지 않는다. metricValue/thresholdValue/sourceMetricId/message는 개시·단계 변경·종료 당시 근거 스냅샷으로 유지한다.

### CollectorHeartbeatEvent — A 생산

공통 필드 + `collectorId:Text`, `timestamp:Time`, `lastCycleStartedAt:Time?`, `lastCycleCompletedAt:Time?`, `cycleInProgress:boolean`. 수집 루프와 별도의 스케줄러에서 10초마다 발행한다. Heartbeat는 Redis 직접 발행하고 outbox로 과거 생존 신호를 재생하지 않는다. consumer는 생성 후 30초 넘은 신호를 현재 생존으로 인정하지 않는다.

## 3. C의 위험도·사건 상태 기계

| ruleId | ruleType | metricName | 조건 |
| --- | --- | --- | --- |
| CONNECTION_RATIO | CONNECTION_RATIO_EXCEEDED | activeConnectionsRatio | 정책의 비율 임계치 |
| SLOW_QUERY_RATE | SLOW_QUERIES_HIGH | slowQueriesPerSecond | 정책의 초당 증가량 임계치 |
| CONNECTION_FAILURE | CONNECTION_FAILURE | connectionStatus | 15초 동안 CONNECTION_FAILED 관측 지속, FATAL |
| COLLECTION_STALE | COLLECTION_STALE | collectionAgeSeconds | 마지막 accepted 시도(없으면 `activationAt`)와 아래 조건을 만족하는 PostgreSQL 최신 수집 시도 중 더 늦은 시각부터 `staleAfterSeconds` 이상 경과, severity CRITICAL |

- `(databaseConfigId,ruleId)`별 OPEN은 최대 1건. 재발은 이전 사건이 종료된 뒤 새 incidentId로 생성한다. 동시에 서로 다른 규칙 사건은 존재할 수 있다.
- 지표 규칙은 각 심각도 임계치의 `>=` 지속 시간을 따로 누적하고 15초(정책 변경 가능) 충족한 가장 높은 단계를 선택한다. 임계치 미달 또는 무효 입력이면 해당 후보 타이머를 초기화한다.
- OPEN 이후 더 높은 단계가 지속 조건을 충족하면 같은 사건의 severity·incidentVersion을 올린다. 낮은 단계로의 전이는 현재 단계 미만 상태가 recoverySeconds 동안 지속되고 하위 단계가 유효할 때 수행한다. warning 미만에서 recoverySeconds 성공 관측이 지속되면 RECOVERED 종료다.
- 연속 관측의 최대 허용 간격은 10초다. consumer가 이미 처리한 `eventId` 또는 현재보다 오래된 metric/version/timestamp를 dedup·순서 판정으로 무시하면 상태, `lastObservedAt`, 후보/복구 타이머, 알림 `eligibleAt`을 전혀 바꾸지 않는다. 새로 수락한 관측 중 간격이 10초를 넘거나 `PARTIAL_FAILURE`이거나 규칙 입력이 null/invalid인 경우에만 후보 타이머와 복구 타이머를 초기화한다. 기존 OPEN 사건은 유지한다. wall-clock 시간 경과만으로 정상 복구시키지 않는다.
- 접속 실패는 실패 관측 시각으로 누적하고 SUCCESS 관측이 15초 연속 유지될 때 종료한다. PARTIAL_FAILURE는 접속 실패 사건의 정상 복구 근거가 아니다.
- stale은 1초 주기의 C 타이머로 계산한다. 기준 시각은 현재 configVersion의 마지막 accepted `collectionAttemptTime`(없으면 `activationAt`)과 PostgreSQL의 적격 최신 수집 시도 중 더 늦은 시각이다. PostgreSQL 기록은 현재 configVersion이고 `activationAt <= collectionAttemptTime <= scanTime`인 경우만 사용하며, 없으면 accepted 시도 또는 activation 기준을 유지한다. `dueAt = 기준 시각 + staleAfterSeconds`이고 `now >= dueAt`이면 STALE로 전환하며, 전환 시각과 새 `COLLECTION_STALE` 사건의 openedAt은 스캔 시각이 아닌 정확한 논리 시각 `dueAt`이다. PostgreSQL 확인만으로 공개 `lastAttemptAt`(마지막 consumed/accepted 시도), latestMetricId, 접속 상태나 후보·복구 타이머를 갱신하지 않는다. 자세한 잠금·후보 조회 규칙은 [C 구현 설명](part-c-risk-notifications.md)을 따른다. 사건은 severity CRITICAL로 열고, `riskLevel`은 모든 OPEN 사건 중 최고 severity로 계산한다. 따라서 stale 사건만/최고일 때 CRITICAL이며, FATAL `CONNECTION_FAILURE`가 함께 OPEN이면 riskLevel은 FATAL이다. 새 SUCCESS 관측이 15초 지속되고 최신성이 회복되면 종료한다. 접속 실패가 계속 와서 시도는 FRESH여도 기존 stale 사건은 성공 관측이 복구 조건을 채울 때까지 유지한다.
- riskLevel은 OPEN 사건의 최고 severity다. 사건이 없으면 모든 활성 규칙에 유효한 최신 입력이 있고 필수 수집 SUCCESS인 경우만 INFO, 그 외 null이다. PAUSED는 항상 null이다. STALE은 관측 불가이며 `COLLECTION_STALE` 타이머 사건 자체의 severity는 CRITICAL이다.
- dataFreshness: enabled=false면 PAUSED; 현재 configVersion에서 accepted collection이 아직 없는 초기 NO_DATA 대상은 위 `dueAt` 전까지 NO_DATA를 유지하며, `now >= dueAt`이면 STALE이다. accepted collection은 관측의 최신성에 따라 FRESH 또는 STALE을 갱신하고, 이후 타이머의 STALE 전환은 적격 PostgreSQL 기록까지 확인한 위 기한을 따른다. PostgreSQL 기록 확인만으로 FRESH로 전환하거나 기존 stale 사건을 회복시키지 않는다. 적격 PostgreSQL 기록과 accepted collection이 모두 없고 기본 staleAfterSeconds=30, activationAt=t=0이면 t=29는 NO_DATA, t=30은 STALE이다. FRESH는 DB 접속 성공과 같지 않다.
- connectionStatus는 SUCCESS이면 UP, CONNECTION_FAILED이면 DOWN, PARTIAL_FAILURE/관측 전은 UNKNOWN이다. lastSuccessAt은 SUCCESS에만 갱신한다.
- 정책 변경은 `CONNECTION_RATIO`·`SLOW_QUERY_RATE`처럼 현재 정책으로 설정할 수 있는 metric-rule 사건만 `POLICY_CHANGED`로 행정 종료하고 그 후보 타이머를 초기화한다. `CONNECTION_FAILURE`·`COLLECTION_STALE` 시스템 사건과 타이머는 정책 변경 중에도 유지한다. 수동 중단·삭제·설정 변경은 api.md의 resolutionReason으로 해당 OPEN 사건을 행정 종료하고 실제 복구 알림을 보내지 않는다. 상태·사건·중복 기록·outbox는 같은 트랜잭션으로 저장한다.

재시작 시 저장된 OPEN 사건과 마지막 metricId를 복구하고 지속 후보 타이머는 초기화한다. 생성 후 staleAfterSeconds보다 오래된 메트릭은 과거 기록으로만 처리한다. `expiresAt`와 `nextAttemptAt`은 DB에 저장해 재시작 후 복구한다. `eligibleAt`은 `expiresAt - 600 seconds`로 다시 계산하는 논리 값이며 별도 V4 column을 전제로 하지 않는다. 재시작·merge·retry는 원래 `expiresAt`을 연장하지 않는다. pending을 재생했다는 이유로 옛 장애 알림/정상 복구를 새로 생성하지 않는다. C는 DB의 최신 메트릭을 확인하여 현재보다 오래된 Redis 이벤트가 최신값을 덮어쓰지 못하게 한다.

## 4. Redis 전달·ACK·복구

| Stream | 생산자 | 그룹 | 목적 |
| --- | --- | --- | --- |
| stream:metrics | A | cg:risk | 위험도/현재 상태 |
| stream:metrics | A | cg:realtime | 실시간 메트릭 |
| stream:statuses | C | cg:realtime | 실시간 상태 |
| stream:incidents | C | cg:notification | 발송 작업 저장 |
| stream:incidents | C | cg:realtime | 실시간 사건 |
| stream:collector-heartbeats | A | cg:risk | 생존 관측 |

그룹은 기능별로 나누고 각 그룹의 worker는 MVP에서 1개다. 이름은 `<group>:<instanceUuid>`. 같은 그룹의 서로 다른 consumer는 이벤트를 나눠 받으므로 두 기능에 모두 전달할 때는 그룹을 분리한다. [Redis 공식 전달 방식](https://redis.io/docs/latest/commands/xreadgroup/).

- 시작: XGROUP CREATE key group 0-0 MKSTREAM, BUSYGROUP이면 이미 있는 그룹 재사용. 기존 consumer의 pending 조회 후 XAUTOCLAIM min-idle=60000ms, count=100으로 오래된 pending 인수, 이후 XREADGROUP COUNT=100 BLOCK=2000 STREAMS ... >. [XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/)을 사용한다.
- 소비 dedup은 `(stream,group,eventId)` unique. risk/notification은 업무 저장과 dedup이 같이 commit된 뒤 XACK. realtime은 broker 로컬 전송 큐 인계 후 ACK하며 브라우저 수신 보장은 REST 재조회로 보완한다.
- Redis record ID는 전송 위치, eventId는 업무 중복 키다. 타임스탬프가 같을 때 Metric은 metricId로 순서 비교한다. 상태는 stateVersion, 사건은 incidentVersion이다. 이미 적용한 동일/과거 버전은 무시한다.
- 일시 DB/Redis 장애는 ACK하지 않고 1/2/4/8/16/30초 backoff, 무기한 재시도·운영 경보. 단순 의존성 장애를 횟수 초과로 버리지 않는다.
- 잘못된 JSON/버전/필수 필드/enum은 즉시 DLQ, 동일 업무 불변식 오류는 최대 5회 후 DLQ. `stream:dead-letter`에 `{sourceStream,recordId,eventId(nullable),reasonCode,failedAt,attemptCount,payload}` 기록 후 ACK. payload는 비밀 제거·64KiB 제한. DLQ 실패 시 원본 ACK 금지.
- 매분 trim: 24시간 전의 경계와 모든 소비 그룹 last-delivered/pending 중 가장 오래된 미완료 위치 중 더 오래된 경계를 MINID로 사용한다. 미처리 데이터를 지우는 MAXLEN은 사용하지 않는다. 저장량 경보·보관은 운영 문서대로다.
- dedup/outbox 31일, metric replay는 30일 이내만 허용. 관리자 재처리는 원래 eventId를 유지한다. 30일보다 오래된 원본은 이력 분석용으로만 보존하고 현재 위험도/알림에 재투입하지 않는다.
- 원자적 발행은 PostgreSQL outbox로 구현하며 Redis와 DB의 분산 트랜잭션을 가정하지 않는다. 늦은 발행으로 같은 이벤트가 여러 번 전달될 수 있다.

## 5. STOMP 클라이언트 계약

Spring simple broker + native WebSocket, endpoint `/ws`, STOMP 1.2. prefix는 broker `/topic`, `/queue`, 사용자 `/user`. 애플리케이션 SEND 목적지는 노출하지 않는다. 단일 실시간 서버만 운영한다. 구독은 서버 메모리에 유지되며 재연결하면 사라진다. [Spring simple broker](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-simple-broker.html).

CONNECT native 헤더는 Authorization:Bearer <access>, accept-version:1.2, heart-beat:10000,10000. C가 ChannelInterceptor에서 검증한다. JWT만 인증 수단으로 사용하므로 STOMP CONNECT에 쿠키 기반 CSRF를 별도로 요구하지 않고 동일 Origin을 엄격히 검사한다. 헤더만 넣으면 자동 인증되는 것으로 가정하지 않는다. [Spring 토큰 인증](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html).

| 목적지 | eventType | data |
| --- | --- | --- |
| /topic/databases/{id}/metrics | MetricUpdated | API Metric 전체 |
| /topic/databases/{id}/status | MonitoringStatusChanged | API StatusSnapshot 전체, deleted 포함 |
| /topic/databases/{id}/incidents | IncidentCreatedEvent / IncidentUpdatedEvent / IncidentResolvedEvent | API Incident 전체 |
| /user/queue/errors | Error | API Error + subscriptionId(nullable) |

전송 envelope는 `{schemaVersion:1,eventId:UUID,eventType:Text,databaseConfigId:Id?,publishedAt:Time,data:object}`. target 없는 사용자 오류만 databaseConfigId=null. MetricUpdated의 eventId는 원본 수집 이벤트 ID, 나머지도 원본 상태 전이 이벤트 ID를 유지한다. Metric의 metricId는 data.id로 매핑한다.

```json
{
  "schemaVersion":1,
  "eventId":"20412297-1a93-44bc-9944-a8d8a03765aa",
  "eventType":"MonitoringStatusChanged",
  "databaseConfigId":12,
  "publishedAt":"2026-09-28T03:00:00.100Z",
  "data":{
    "databaseConfigId":12,"configVersion":2,"deleted":false,"enabled":true,
    "connectionStatus":"UP","dataFreshness":"FRESH","riskLevel":"INFO",
    "lastAttemptAt":"2026-09-28T03:00:00.000Z","lastSuccessAt":"2026-09-28T03:00:00.000Z",
    "latestMetricId":501,"openIncidentIds":[],"stateVersion":8,"updatedAt":"2026-09-28T03:00:00.080Z"
  }
}
```

- 클라이언트는 SUBSCRIBE에 고유 id, ack:auto를 사용한다. simple broker의 RECEIPT/과거 재전송/개별 ACK에 의존하지 않는다. v0.1의 RECEIPT 전제는 제거한다.
- 연결 후 errors 및 대상 topic 구독을 먼저 보내고 메시지를 버퍼링하면서 REST latest/status를 조회한다. REST를 적용한 뒤 더 최신 이벤트만 적용한다. 2초 후 한 번 더 재조회하고 이후 30초마다 대조한다. 이는 구독 등록과 조회 사이 누락을 최종적으로 보정하며 모든 중간 프레임 수신을 보장하지는 않는다.
- 재연결은 1/2/4/8/16/30초 기본 지연에 0~20% jitter, 연결 안정 30초 후 backoff 초기화. Access 만료면 Refresh 1회 후 새 CONNECT·재구독. 갱신 실패면 로그인으로 이동한다.
- 서버 재시작/네트워크 단절 뒤에는 항상 상태 재조회. 차트 누락 구간은 history로 채우고 id로 dedup. 24시간보다 긴 공백은 여러 요청으로 나누되 30일 보관 경계보다 오래된 구간은 ‘보관 기간 밖’으로 표시한다.
- Metric과 상태는 독립 순서다. 상태 비교는 configVersion/stateVersion, Metric 비교는 configVersion/(timestamp,id), Incident는 incidentVersion. 각 기준은 별개로 유지한다. Incident의 같은 버전 REST 응답은 lastObservedAt의 최댓값만 갱신할 수 있다. 구 버전 config의 새 도착은 버린다.
- disabled 대상도 PAUSED를 구독할 수 있다. 삭제 상태 이벤트를 받거나 주기적 status가 404이면 구독 해제·대상 목록 재조회. 204 latest는 아직 관측 없음이다.
- 인증/프로토콜 오류는 STOMP ERROR JSON `{code,message,requestId}` 후 종료. 구독 오류는 errors 큐의 Error 후 해당 구독 거절. code는 AUTH_REQUIRED/ACCESS_TOKEN_EXPIRED/INVALID_TOKEN/SESSION_REVOKED/FORBIDDEN/DATABASE_NOT_FOUND/VALIDATION_ERROR다. errors 큐 미등록이면 ERROR 후 종료한다.
- 토큰 exp에는 즉시 세션 종료, 사용자/세션 변경은 내부 통지 즉시 및 10초 검증으로 보정. 프론트가 알림을 놓쳐도 서버에서 구독·발송 권한을 검증한다.

## 6. 외부 알림·수신처

모든 활성 개인 Push 구독과 모든 활성 Slack 수신처가 모든 대상 사건을 받는다. 개인 Push는 해당 사용자/등록 세션도 유효해야 한다. MVP에서는 사용자별 대상 필터를 제공하지 않는다.

- OPEN은 즉시 1회. 심각도 상승은 마지막 성공 개시/상승 알림에서 `notificationCooldownSeconds`(기본 300초, 60~3600초) 안이면 첫 대기 작업의 `eligibleAt`을 다음 허용 시각으로 정하고, `expiresAt=eligibleAt+600초`로 저장한다. 같은 사건·수신처의 더 새로운 비-FATAL 상승은 최신 incidentVersion/내용으로 병합하되 처음 정한 `eligibleAt`·`expiresAt`을 뒤로 미루지 않는다. `now < eligibleAt`은 cooldown 대기이고 `eligibleAt <= now < expiresAt`만 외부 발송·재시도 창이다. FATAL 상승은 대기 중인 비-FATAL 상승 작업을 CANCELLED로 대체하고 cooldown을 우회해 즉시 보내며, 하향은 알리지 않는다. 지속 장애에 정기 재알림은 없다.
- RECOVERED는 cooldown을 우회해 즉시 1회 만들고, 단 해당 수신처에 개시/상승 알림을 성공 발송한 적이 있을 때만 보낸다. POLICY_CHANGED/MONITORING_PAUSED/CONFIG_CHANGED/TARGET_DELETED 종료는 알리지 않는다.
- 발송 직전에 저장된 사건 버전·상태, 수신처/사용자/세션 활성 여부를 검사한다. 이미 복구된 사건의 미발송 OPEN/상승은 CANCELLED, 오래된 상승은 기존 대기 작업의 최신 내용으로 병합, 삭제 수신처도 CANCELLED다. `now >= expiresAt`인 작업은 CANCELLED이며, 생성 시각이 아니라 저장된 `eligibleAt` 기준의 600초 창을 사용한다.
- 작업 고유 키 `(incidentId,incidentVersion,channel,recipientId)`로 저장 후 Redis ACK. `eligibleAt`에 첫 시도, 이후 +5/+30/+120초 재시도, 429의 Retry-After가 더 길어도 `expiresAt` 이후로는 연장하지 않는다. `404/410` 수신처는 비활성화, 그 외 영구 4xx는 FAILED, 5xx/timeout만 재시도한다. `eligibleAt`은 immutable logical value `expiresAt - 600 seconds`이며 worker는 `nextAttemptAt`만 변경한다. `now >= expiresAt`인 발송·재시도는 `CANCELLED`이며 창을 재개하거나 연장하지 않는다.
- 외부 서비스의 성공 응답을 잃은 경우 재시도로 중복 수신될 수 있다. 내부 사건/작업 중복 방지와 외부 정확히 한 번 배달을 동일하게 표현하지 않는다.

`eligibleAt` is the immutable logical value `expiresAt - 600 seconds`. A worker
may move only `nextAttemptAt`; any send or retry at `now >= expiresAt` is
`CANCELLED` and does not reopen or extend the window. The Web Push public
payload uses the same-origin relative `url` field, for example
`/incidents/<UUID>`; `path` is not a contract field.

### Slack

provider=SLACK, Incoming Webhook HTTP POST application/json. `text`는 `[심각도] 표시명 · 규칙 · 발생/복구 시각 · 사건 링크`, `blocks`는 plain_text section으로 동일 메시지, unfurl_links=false/unfurl_media=false. 표시명과 설명의 `<`, `>`, `&`는 escape하여 임의 mention을 막는다. 채널/username override는 받지 않는다. 성공은 HTTP 200 + 본문 ok. URL별 1초에 1회 직렬 발송, 그 외 작업은 큐 대기. [Slack 공식 Webhook 형식](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/).

### Web Push

VAPID P-256([RFC 8292](https://www.rfc-editor.org/rfc/rfc8292)), aes128gcm 암호화([RFC 8291](https://www.rfc-editor.org/rfc/rfc8291)), TTL=600초. C가 보안 문서의 수신처 제한을 적용하고 라이브러리로 표준 암호화를 수행한다. 프론트는 사용자 클릭 뒤 권한 요청, 서비스 워커 등록, PushSubscription을 API로 전송한다. 사용자 거부는 장애로 취급하지 않고 구독 안 됨으로 표시한다.

```json
{
  "schemaVersion":1,"deliveryId":81,"incidentId":"984b0ae3-37e9-46b1-a709-87bfb95b9a1a",
  "type":"INCIDENT_OPENED","title":"[WARNING] 운영 MariaDB",
  "body":"연결 사용 비율이 경고 기준을 초과했습니다.",
  "url":"/incidents/984b0ae3-37e9-46b1-a709-87bfb95b9a1a",
  "tag":"incident:984b0ae3-37e9-46b1-a709-87bfb95b9a1a","sentAt":"2026-09-28T03:00:15.000Z"
}
```

type은 INCIDENT_OPENED/SEVERITY_INCREASED/INCIDENT_RESOLVED. body 최대 300자, title 최대 100자, 전체 payload 3KiB 이하. url은 `/incidents/<UUID>` 상대 경로만 허용하고 서비스 워커는 같은 origin으로만 이동한다. tag는 같은 사건 알림을 교체하는 데 사용한다. 비로그인 클릭은 로그인 후 해당 경로로 이동한다. Web Push·Slack 본문에 토큰·DB 주소·계정·SQL 원문은 포함하지 않는다.

지원 검수 대상은 desktop Chrome/Edge, Android Chrome, iOS/iPadOS 16.4 이상 홈 화면에 설치한 웹 앱이다. iOS의 설치·사용자 동작 조건은 [WebKit 공식 안내](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)를 따른다. OS/브라우저 알림 권한이 필요하며 HTTPS에서 검수한다. 미지원/거부 기기는 대시보드와 Slack을 이용한다. 실제 모바일 전달은 각 플랫폼 기기로 검수한다.

## 8. 기준 커밋의 현재 이벤트 구현

아래는 이전 발행 코드이며 v1 DTO와 혼용하지 않는다.

| 항목 | 현재 구현 |
| --- | --- |
| 수집 Stream | `stream:metrics`, 설정 `app.redis.stream-key` |
| 사건 Stream | `stream:incidents`, 설정 `app.redis.incident-stream-key`의 코드 기본값 |
| Redis 레코드 형식 | 두 발행기 모두 필드 하나: `payload` = DTO를 직렬화한 JSON 문자열 |
| MetricCollectedEvent | databaseConfigId, databaseName, host, port, timestamp, collectionAttemptTime, cpuUsage, memoryUsage, activeConnections, maxConnections, qps, slowQueries, threadsRunning, storageBytes, responseTimeMs, collectionStatus, errorMessage |
| IncidentCreatedEvent | incidentId(UUID), databaseConfigId, databaseName, severity, ruleType, message, metricName, metricValue, thresholdValue, timestamp |
| 생성 위치 | 수집 스케줄러가 메트릭 저장→발행→위험도 평가→사건 발행→FATAL 차단 순으로 직접 호출 |
| 부족한 공통 필드 | schemaVersion, eventId, metricId, 마지막 성공 시각, 사건 상태·복구·원인 이벤트 ID |
| 발행 실패 | Redis 오류는 로그 후 null 반환. 스케줄러는 성공 여부를 확인하지 않고 진행. 저장/발행 사이 복구 기록 없음 |
| 소비·복구 | Consumer Group, ACK, pending 복구, STOMP 서버 구현 없음 |

근거: [MetricCollectedEvent](../backend/src/main/java/com/example/monitoring/dto/MetricCollectedEvent.java), [IncidentCreatedEvent](../backend/src/main/java/com/example/monitoring/dto/IncidentCreatedEvent.java), [사건 발행기](../backend/src/main/java/com/example/monitoring/infrastructure/redis/IncidentPublisher.java), [RedisConfig](../backend/src/main/java/com/example/monitoring/infrastructure/redis/RedisConfig.java). 이전 통합 초안에서 사용한 `backend/src/main/java/com/example/monitoring/infrastructure/redis/RedisStreamPublisher.java` 경로는 현재 트리에 없는 역사적 참조이므로 활성 코드 링크로 취급하지 않는다.

현재 `databaseName`에는 실제 MariaDB 스키마가 아닌 `DatabaseConfig.name` 표시명이 들어간다. 시간은 LocalDateTime + 직접 구성한 ObjectMapper이므로 날짜 문자열/배열 여부와 시간대부터 샘플로 확인해야 한다. 새로운 소비자가 임의로 ISO UTC라고 가정하면 안 된다.
