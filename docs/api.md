# REST API 규격 초안 v0.2

**팀 배포용 v1 개발 기준**. 아래 경로·DTO·권한·수치는 구현할 목표이며, 기준 커밋의 현재 동작은 마지막 절에 별도 보존했다.

[전체 기준](integration-contract-draft.md) · [이벤트](events.md) · [보안](integration-security.md) · [운영](integration-operations.md)

정상·warmup·실패·사건·Heartbeat 예제는 [contract-examples.json](contract-examples.json)의 fixtures에서 바로 사용할 수 있다.

## 1. 공통 타입·HTTP 계약

- `Id`: JSON 양의 정수, 최대 9007199254740991. `Uuid`: 소문자 UUID 문자열. `Time`: UTC `YYYY-MM-DDTHH:mm:ss.SSSZ`. `Text`: UTF-8 문자열. `?`는 응답 nullable, 요청에서는 별도로 ‘선택’ 표기가 있어야 생략 가능하다.
- 아래 응답 DTO의 필드는 모두 포함한다. 요청의 알려지지 않은 필드·enum·중복 JSON 키, 범위 밖 숫자, 잘못된 날짜는 400. Content-Type이 필요한 본문은 application/json만 허용(그 외 415).
- 성공은 DTO/배열 직접 반환, 페이지는 `{items:T[],page:int,size:int,totalElements:int,totalPages:int}`. page 기본 0·최대 10000, size 기본 20·1~100, 빈 페이지 totalPages=0. 페이지가 끝을 넘으면 items=[]다.
- 생성 201 + Location, 조회/갱신 200, 삭제/로그아웃 및 아직 수집 없음 204(본문 없음). 알 수 없는 자원 404. 모든 날짜 범위는 `start <= timestamp < end`.
- 보호 API는 Access Bearer. 쿠키로 보호 REST를 인증하지 않는다. USER/ADMIN 모두 대상 전체 조회 가능, 관리 작업은 ADMIN. 인증 없음 401, 역할 불충분 403, 타인 개인 자원은 404.
- 응답에 `X-Request-Id`, `Cache-Control:no-store`. 요청마다 서버 UUID를 생성한다. JSON 본문 최대 64KiB(초과 413), 문자열은 명시한 길이의 Unicode code point 기준; 비밀번호는 보안 문서의 바이트 제한을 따른다.
- 클라이언트 자동 재시도는 GET과 명시적 401 갱신에 한정한다. POST/PATCH/PUT/DELETE 타임아웃은 먼저 상태 재조회한다. 로그인 외 일반 API는 동일 사용자 초당 30회, burst 60회; 429 Retry-After는 초 단위다.
- GET latest/recent/history는 각각 보호 API다. 액세스 판정·입력 오류도 공통 오류 DTO를 사용한다.

### 공통 오류 DTO

```json
{
  "code": "VALIDATION_ERROR",
  "message": "요청 값을 확인해 주세요.",
  "requestId": "f416c138-5cdf-451c-8b31-ae9d59eaaeba",
  "fieldErrors": [{"field":"port","code":"OUT_OF_RANGE","message":"1~65535 범위여야 합니다."}]
}
```

code는 아래 목록, message는 안전한 사용자용 설명, fieldErrors는 `{field,code,message}` 배열이며 없으면 []. JDBC/SQL 예외 원문·스택·비밀은 노출하지 않는다. 입력 오류의 field code는 REQUIRED/INVALID_FORMAT/OUT_OF_RANGE/UNKNOWN_FIELD/INVALID_VALUE다.

| HTTP | code | 처리 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR, INVALID_TIME_RANGE, RESULT_LIMIT_EXCEEDED | 입력 또는 기간 축소 |
| 401 | AUTH_REQUIRED, INVALID_CREDENTIALS, ACCESS_TOKEN_EXPIRED, INVALID_TOKEN, REFRESH_TOKEN_INVALID, SESSION_REVOKED | ACCESS_TOKEN_EXPIRED만 갱신 1회; 나머지는 로그인 |
| 403 | FORBIDDEN, CSRF_INVALID, ORIGIN_NOT_ALLOWED | 권한/요청 출처 처리 |
| 404 | DATABASE_NOT_FOUND, INCIDENT_NOT_FOUND, USER_NOT_FOUND, SUBSCRIPTION_NOT_FOUND, WEBHOOK_NOT_FOUND | 자원 없음 |
| 409 | EMAIL_ALREADY_EXISTS, CONFIG_VERSION_CONFLICT, POLICY_VERSION_CONFLICT, LAST_ADMIN, RESOURCE_LIMIT_EXCEEDED, COLLECTION_PAUSED | 최신 상태 확인 |
| 413 / 415 | PAYLOAD_TOO_LARGE / UNSUPPORTED_MEDIA_TYPE | 본문 수정 |
| 429 | RATE_LIMITED | Retry-After 이후 |
| 500 / 503 | INTERNAL_ERROR / DEPENDENCY_UNAVAILABLE | requestId로 추적, 수동 재시도 |

## 2. 인증·사용자 — B

| Method / 경로 | 요청 | 성공 | 권한 |
| --- | --- | --- | --- |
| GET `/api/v1/auth/csrf` | 없음 | 200 `{csrfToken:Text}` + csrfSession 쿠키 | 공개 |
| POST `/api/v1/auth/signup` | Signup | 201 User | 공개 + CSRF |
| POST `/api/v1/auth/login` | Login | 200 TokenResponse + Refresh 쿠키 | 공개 + CSRF |
| POST `/api/v1/auth/refresh` | 본문 없음, Refresh 쿠키 | 200 TokenResponse + 회전 쿠키 | Refresh + CSRF |
| POST `/api/v1/auth/logout` | 본문 없음, Refresh 쿠키 | 204, 세션 폐기·쿠키 삭제 | CSRF; 이미 로그아웃도 204 |
| GET `/api/v1/auth/me` | 없음 | 200 User | USER/ADMIN |
| GET `/api/v1/users` | page,size | 200 User 페이지, id ASC | ADMIN |
| PATCH `/api/v1/users/{id}/role` | `{role:USER 또는 ADMIN}` | 200 User | ADMIN |
| PATCH `/api/v1/users/{id}/status` | `{enabled:boolean}` | 200 User | ADMIN |

Signup은 email(Text, 1~254, 유효한 이메일), password(보안 문서), displayName(Text, 1~100) 모두 필수다. role 입력은 400. email은 trim·소문자 변환 후 고유, displayName은 trim 후 길이를 검사한다. Login은 email/password 필수, 실패 원인을 나누지 않고 INVALID_CREDENTIALS다. 로그인은 토큰과 새 세션을 만들고 가입은 토큰을 자동 발급하지 않는다.

User: `{id:Id,email:Text,displayName:Text,role:USER|ADMIN,enabled:boolean,createdAt:Time,updatedAt:Time}`. 마지막 활성 Admin을 일반 사용자로 내리거나 비활성화할 수 없다(409 LAST_ADMIN). 역할/활성 변경 시 해당 사용자의 세션을 모두 폐기한다.

TokenResponse: `{accessToken:Text,tokenType:"Bearer",expiresIn:900,user:User}`. Refresh는 JSON에 넣지 않는다. CSRF·동시 갱신·쿠키·만료·키는 [보안 규격](integration-security.md)에 고정되어 있다.

## 3. 모니터링 대상 DB — B / Ping 수행 A

| Method / 경로 | 요청 | 성공 | 권한 |
| --- | --- | --- | --- |
| POST `/api/v1/databases` | DatabaseCreate | 201 Database | ADMIN |
| GET `/api/v1/databases` | page,size, enabled(선택 boolean) | 200 Database 페이지, id ASC, 삭제 제외 | USER/ADMIN |
| GET `/api/v1/databases/{id}` | 없음 | 200 Database | USER/ADMIN |
| PATCH `/api/v1/databases/{id}` | configVersion + 변경할 설정 한 개 이상 | 200 Database | ADMIN |
| DELETE `/api/v1/databases/{id}` | 없음 | 204 soft delete, 이미 삭제/없는 ID도 204 | ADMIN |
| POST `/api/v1/databases/{id}/ping` | 본문 없음 | 200 PingResult | ADMIN |

| 설정 필드 | 요청 타입·범위 | 생성 기본값 / 수정 |
| --- | --- | --- |
| name | Text, trim 후 1~100 | 필수 / 선택 |
| host | DNS 이름 또는 IP 문자열, 1~253; scheme/path/query/공백 금지 | 필수 / 선택 |
| port | int 1~65535 | 필수 / 선택 |
| databaseName | Text?, 1~100, `[A-Za-z0-9_$-]+` | 선택 null / 명시적 null 허용 |
| username | Text 1~100 | 필수 / 선택 |
| password | UTF-8 1~4096 byte, 원문 trim 금지 | 필수 / 선택, null·빈 문자열 금지 |
| enabled | boolean | 선택 true / 선택 |
| configVersion | Id | 생성 요청 금지 / 수정 필수, 현재와 다르면 409 |

대상 name은 고유하지 않아도 되며 ID로 구별한다. 등록 가능한 미삭제 대상은 20개까지, 초과 시 409 RESOURCE_LIMIT_EXCEEDED. 접속 실패여도 설정 등록은 성공시킨다. 등록·수정 응답이 연결 성공을 뜻하지 않는다. allowed CIDR와 포트 검사는 [보안 규격](integration-security.md)을 따른다.

Database: `{id:Id,name:Text,host:Text,port:int,databaseName:Text?,enabled:boolean,configVersion:Id,connectionStatus:UP|DOWN|UNKNOWN,lastAttemptAt:Time?,lastSuccessAt:Time?,createdAt:Time,updatedAt:Time}`. username/password/암호문은 반환하지 않는다. 수정 화면의 계정명·비밀번호 입력란은 비워 두고 새 입력이 있을 때만 전송한다.

모든 설정 수정은 configVersion을 1 증가시키고 이전 작업 결과의 현재 상태 반영을 차단한다. 해당 버전의 최신 관측과 타이머는 초기화한다. C는 기존 OPEN 사건을 CONFIG_CHANGED(비활성화는 MONITORING_PAUSED)로 관리 종료하고, 다음 새 관측부터 다시 평가한다. 이는 자동 복구와 구분한다. DELETE는 configVersion 증가·enabled=false·deletedAt 기록과 연결된다.

Ping은 수집 비활성 대상에도 허용되며 제한된 1회 진단만 수행한다. 정기 수집의 timestamp·connectionStatus·risk·enabled를 갱신하지 않는다. 1대당 10초에 1회, 작업 제한 15초다.

PingResult: `{databaseConfigId:Id,status:UP|DOWN,version:Text?,responseTimeMs:int,timestamp:Time,errorCode:Text?,errorMessage:Text?}`. 성공은 오류 null. 대상 접속 실패는 200+DOWN, version=null이고 AUTH_FAILED/CONNECT_TIMEOUT/CONNECTION_REFUSED/QUERY_FAILED/UNKNOWN 중 errorCode를 반환한다. 자체 시스템 저장소 장애는 503이다.

v1은 기존 `/api/v1/projects/**`와 `GET /api/databases/{id}/ping`을 제공하지 않는다. 수동 수집 중단/재개는 PATCH의 enabled=false/true를 사용한다. 서버의 외부 MariaDB 클라이언트를 차단하는 기능은 없다.

## 4. 메트릭 — A

| Method / 경로 | 입력 | 정렬·응답 |
| --- | --- | --- |
| GET `/api/v1/metrics/{dbId}/latest` | 없음 | 현재 configVersion의 최신 Metric 200, 없음 204 |
| GET `/api/v1/metrics/{dbId}/recent` | limit 기본 50, 1~1000 | timestamp DESC, id DESC; Metric[] |
| GET `/api/v1/metrics/{dbId}/history` | start/end 필수 UTC, 최대 24시간 | timestamp ASC, id ASC; Metric[] |

권한은 USER/ADMIN. latest의 미삭제 대상이 없으면 404. recent/history는 보관 중인 삭제 대상의 과거 자료도 조회 가능하며 대상 레코드 자체가 없으면 404다. history start<end, 최대 20,000건을 넘으면 400 RESULT_LIMIT_EXCEEDED, 조용한 truncation 금지. 데이터 없음은 배열 []. 집계/다운샘플링 없이 원본 스냅샷을 반환한다.

Metric 필드는 [지표 사전](events.md)과 아래 예제로 고정한다. 모든 수치는 유한한 비음수, nullable 필드는 null 이유를 unavailableMetrics에 표시한다. id는 내부 이벤트 metricId와 같다. 응답 configVersion은 해당 수집에 사용한 설정 버전이다.

```json
{
  "id":501,
  "databaseConfigId":12,
  "configVersion":2,
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

## 5. 최신 상태·위험 정책·사건 — C

| Method / 경로 | 요청 | 응답·권한 |
| --- | --- | --- |
| GET `/api/v1/databases/{id}/status` | 없음 | 200 StatusSnapshot, USER/ADMIN |
| GET `/api/v1/databases/{id}/risk-policy` | 없음 | 200 RiskPolicy, USER/ADMIN |
| PUT `/api/v1/databases/{id}/risk-policy` | PolicyWrite 전체 | 200 RiskPolicy, ADMIN |
| GET `/api/v1/incidents` | databaseConfigId,start,end,severity,status,page,size 모두 선택 | 200 Incident 페이지, openedAt DESC·incidentId ASC, USER/ADMIN |
| GET `/api/v1/incidents/{incidentId}` | UUID | 200 Incident, USER/ADMIN |

StatusSnapshot: `{databaseConfigId:Id,configVersion:Id,deleted:boolean,enabled:boolean,connectionStatus:UP|DOWN|UNKNOWN,dataFreshness:FRESH|STALE|NO_DATA|PAUSED,riskLevel:INFO|WARNING|CRITICAL|FATAL|null,lastAttemptAt:Time?,lastSuccessAt:Time?,latestMetricId:Id?,openIncidentIds:Uuid[],stateVersion:Id,updatedAt:Time}`. 미관측 대상도 200이다. 현재 configVersion에서 accepted collection이 아직 없으면 `activationAt`을 기준으로 `now < activationAt + staleAfterSeconds`일 때만 enabled 대상의 `dataFreshness=NO_DATA`로 표시한다. accepted collection이 있으면 마지막 `collectionAttemptTime`을 기준으로 stale을 계산한다. 정확히 각 기준 시각에 `staleAfterSeconds`가 경과하면 `STALE`로 전환하고 `COLLECTION_STALE` 타이머 사건을 severity=CRITICAL로 연다. `riskLevel`은 모든 OPEN 사건 중 최고 severity이므로 stale 사건만/최고일 때 CRITICAL이며, 동시에 FATAL 사건이 OPEN이면 FATAL이다. 기본 정책에서는 activationAt=t=0일 때 t=29초가 NO_DATA이고 t=30초가 STALE이다. enabled=false이면 항상 PAUSED이며 riskLevel=null이다. 삭제된 대상은 404다. 일반 REST 응답의 deleted는 false다. stateVersion은 대상별 증가하며 설정 변경/재시작에도 감소하지 않는다.

PolicyWrite: `{version:Id,staleAfterSeconds:int,notificationCooldownSeconds:int,rules:Rule[]}`. RiskPolicy는 위 필드에 databaseConfigId·updatedAt을 추가한다. DB 생성 시 기본 정책 version=1을 제공한다. `staleAfterSeconds`는 30~300, `notificationCooldownSeconds`는 기본 300초이며 60~3600초다. cooldown 중인 상승 전달은 마지막 성공 개시/상승 알림 뒤의 `eligibleAt`까지 대기하고, 같은 사건·수신처의 최신 비-FATAL 상승으로 병합해도 처음 정한 `eligibleAt`을 미루지 않는다. FATAL 상승은 대기 중 비-FATAL 상승 작업을 취소/대체하고 즉시 발송한다. 이 스케줄링 시각은 내부 저장·전달 규칙을 따른다. version이 다르면 409 POLICY_VERSION_CONFLICT.

Rule은 `{ruleId:Text,metricName:Text,operator:"GTE",warningThreshold:number,criticalThreshold:number,fatalThreshold:number?,sustainSeconds:int,recoverySeconds:int,enabled:boolean}`. rules에는 CONNECTION_RATIO와 SLOW_QUERY_RATE 두 개를 정확히 한 번씩 포함한다. 임의 규칙 추가/삭제는 400, enabled로 활성화한다.

| ruleId | metricName | warning / critical / fatal | 초기 sustain / recovery |
| --- | --- | --- | --- |
| CONNECTION_RATIO | activeConnectionsRatio | 0.80 / 0.90 / 0.95 | 15 / 15초 |
| SLOW_QUERY_RATE | slowQueriesPerSecond | 1.0 / 5.0 / null | 15 / 15초 |

연결 비율 임계치는 0<warning<critical<fatal≤1. Slow Query는 0<warning<critical, fatal=null 고정. sustain/recovery는 5~300초의 5 배수다. POLICY 변경은 `CONNECTION_RATIO`·`SLOW_QUERY_RATE`처럼 현재 정책으로 설정할 수 있는 metric-rule의 OPEN 사건만 `POLICY_CHANGED`로 관리 종료하고 해당 후보 타이머를 초기화한다. `CONNECTION_FAILURE`와 `COLLECTION_STALE` 같은 시스템 사건은 종료하거나 타이머를 초기화하지 않고 유지한다. disabled 규칙은 판단하지 않는다. 시스템 규칙의 접속 실패 지속 15초/복구 15초는 수정 API에 노출하지 않고 `staleAfterSeconds`만 수정 가능하다.

Incident 필수 필드: `{incidentId:Uuid,databaseConfigId:Id,databaseName:Text,ruleId:Text,ruleType:Text,severity:WARNING|CRITICAL|FATAL,status:OPEN|RESOLVED,openedAt:Time,lastObservedAt:Time,resolvedAt:Time?,resolutionReason:RECOVERED|POLICY_CHANGED|MONITORING_PAUSED|CONFIG_CHANGED|TARGET_DELETED|null,metricName:Text,metricValue:number?,thresholdValue:number?,sourceMetricId:Id?,message:Text,incidentVersion:Id}`. databaseName은 사건 개시 당시 표시명이며 이름 변경 후에도 보존한다. severity는 현재 또는 종료 직전 수준이다. 행정상 종료도 status=RESOLVED지만 resolutionReason으로 정상 복구와 구분한다.

사건 목록의 start/end는 openedAt 기준이고 둘 다 생략 시 최근 24시간, 한쪽만 오면 400, 최대 30일. status=OPEN도 같은 기간 필터를 적용하므로 현재 OPEN 전체는 StatusSnapshot.openIncidentIds로 찾는다. status/severity는 단일 enum, 없는 필터 대상 ID는 빈 목록. 삭제된 대상의 사건도 보관 기간 동안 조회 가능하다.

## 6. 감사·접속 이력 — B

| Method / 경로 | 요청 | 응답·권한 |
| --- | --- | --- |
| GET `/api/v1/audit-logs` | actorId,databaseConfigId,action,result,start,end,page,size | Audit 페이지, occurredAt DESC·id DESC, ADMIN |
| GET `/api/v1/access-logs` | actorId,start,end,page,size | AccessLog 페이지, occurredAt DESC·id DESC, ADMIN |

모든 필터 선택, 기간은 사건과 같은 기본 24시간/최대 30일/[start,end). Audit: `{id:Id,actorId:Id?,action:Text,targetType:USER|DATABASE|POLICY|PUSH_SUBSCRIPTION|WEBHOOK|SESSION,targetId:Text?,databaseConfigId:Id?,result:SUCCESS|FAILURE,occurredAt:Time,clientIp:Text,requestId:Uuid,summary:Text}`. action의 고정 목록은 보안 문서. targetId는 서로 다른 ID형을 표현하기 위해 문자열이다.

AccessLog: `{id:Id,actorId:Id?,method:Text,path:Text,statusCode:int,durationMs:int,clientIp:Text,occurredAt:Time,requestId:Uuid}`. path는 query string 제외. 비밀·본문·쿠키·Authorization을 저장하지 않는다. 일반 GET 조회 기록과 실제 변경 감사는 다른 테이블이다.

## 7. 알림 수신처·결과 — C

| Method / 경로 | 요청 | 응답·권한 |
| --- | --- | --- |
| GET `/api/v1/notifications/push-config` | 없음 | 200 `{publicKey:Text}`, USER/ADMIN |
| GET `/api/v1/notifications/push-subscriptions` | 없음 | 200 PushSubscription[] (본인의 활성 구독), USER/ADMIN |
| POST `/api/v1/notifications/push-subscriptions` | PushInput | 신규 201 / 본인 동일 endpoint 갱신 200, USER/ADMIN |
| DELETE `/api/v1/notifications/push-subscriptions/{id}` | 없음 | 204 본인 소유 삭제, 타인/없는 ID 404 |
| GET `/api/v1/notifications/webhooks` | page,size | 200 Webhook 페이지, id ASC·삭제 제외, ADMIN |
| POST `/api/v1/notifications/webhooks` | WebhookInput | 201 Webhook, ADMIN |
| PATCH `/api/v1/notifications/webhooks/{id}` | name/url/enabled 중 한 개 이상 | 200 Webhook, ADMIN |
| DELETE `/api/v1/notifications/webhooks/{id}` | 없음 | 204 삭제, 없는 ID도 204, ADMIN |
| GET `/api/v1/notifications/deliveries` | incidentId,channel,status,start,end,page,size | 200 Delivery 페이지, createdAt DESC·id DESC, ADMIN |

PushInput: `{endpoint:Text,expirationTime:int?,keys:{p256dh:Text,auth:Text}}` 모두 필수. endpoint는 HTTPS URL 최대 2048, p256dh는 base64url 디코딩 후 65바이트, auth는 16바이트. expirationTime은 epoch milliseconds 또는 null로 외부 Web Push 표준에 맞춘 시간 형식의 예외다. 과거 만료값은 400. 본인당 최대 10개, 타인의 활성 endpoint 재등록은 409 RESOURCE_LIMIT_EXCEEDED. 기존 구독이 해제/로그아웃으로 비활성화되면 새 소유자의 별도 ID로 등록할 수 있다. 이전 tombstone과 발송 이력의 소유자를 변경하지 않는다.

PushSubscription: `{id:Id,createdAt:Time,updatedAt:Time,expirationTime:int?}`. endpoint/keys 미반환. 전체 대상의 사건 알림을 해당 사용자 기기로 보낸다. 권한 철회·계정 비활성화·로그아웃 후에는 해당 로그인 세션에서 등록한 구독을 비활성화하며 새 로그인에서 재등록한다.

WebhookInput: `{name:Text,provider:"SLACK",url:Text,enabled:boolean}`. name trim 후 1~100, url 최대 2048; 모두 필수. provider는 수정 불가. 최대 10개. Webhook: `{id:Id,name:Text,provider:"SLACK",enabled:boolean,createdAt:Time,updatedAt:Time}`. URL·토큰 미반환. Slack 연결/발송 규격은 events.md, URL 검사는 보안 규격을 따른다.

Delivery: `{id:Id,incidentId:Uuid,incidentVersion:Id,channel:WEB_PUSH|SLACK,recipientId:Id,status:PENDING|SENT|FAILED|CANCELLED,attemptCount:int,lastErrorCode:Text?,createdAt:Time,sentAt:Time?}`. 알림 결과 목록 기간은 createdAt 기준, 기본 24시간/최대 30일. 오류 코드는 TIMEOUT/RATE_LIMITED/RECIPIENT_GONE/REJECTED/PROVIDER_ERROR다. 수신처 삭제·이벤트 노후화는 CANCELLED이고 실패 재시도 대상으로 넣지 않는다. `eligibleAt`·`expiresAt`은 내부 스케줄러 컬럼이며 공개 Delivery 응답에는 추가하지 않는다.

## 8. 기준 커밋의 현재 REST 구현

아래는 이전 코드 설명이며 위 v1 목표와 구분한다.

| Method | 현재 경로 | 입력 | 응답·상태 |
| --- | --- | --- | --- |
| GET | `/api/v1/metrics/{dbId}/latest` | dbId: Long | 200 MetricResponseDto, 없으면 본문 없는 404 |
| GET | `/api/v1/metrics/{dbId}/recent` | limit: int, 기본 50 | 200 DTO 배열, 최신순; 범위 검증 없음 |
| GET | `/api/v1/metrics/{dbId}/history` | start/end 필수, ISO LocalDateTime | 200 DTO 배열, 오래된 순; BETWEEN으로 양 끝 포함 |
| GET | `/api/databases/{id}/ping` | id: Long | 200 DbPingResponseDto; 대상 없으면 본문 없는 404. 접속 실패도 200 + DOWN |
| GET | `/api/v1/projects/{id}/block-status` | id는 DatabaseConfig ID | 200 BlockStatusResponseDto; 없으면 404 `{error:문자열}` |
| GET | `/api/v1/projects/blocked` | 없음 | 200 BlockStatusResponseDto 배열 |
| POST | `/api/v1/projects/{id}/block` | reason, approvedBy | 200 차단 결과; 없는 대상 404, 이미 차단 409 |
| POST | `/api/v1/projects/{id}/unblock` | reason, approvedBy | 200 해제 결과; 없는 대상 404, 미차단 409 |
| POST | `/api/v1/projects/{id}/toggle` | reason, approvedBy | 200 전환 결과; 없는 대상 404 |

코드 근거: [MetricController](../backend/src/main/java/com/example/monitoring/controller/MetricController.java), [DatabasePingController](../backend/src/main/java/com/example/monitoring/controller/DatabasePingController.java), [ProjectIsolationController](../backend/src/main/java/com/example/monitoring/controller/ProjectIsolationController.java).

현재 DTO:

- `MetricResponseDto`: id, databaseConfigId, timestamp, cpuUsage, memoryUsage, activeConnections, maxConnections, qps, slowQueries, threadsRunning, storageBytes, responseTimeMs, collectionStatus, errorMessage.
- `DbPingResponseDto`: databaseConfigId, status, version, responseTimeMs, timestamp, errorMessage.
- `BlockStatusResponseDto`: databaseConfigId, databaseName, status, enabled, activeBlock. activeBlock은 null 또는 `{blockRecordId, incidentId, severity, blockType, reason, blockedBy, blockedAt}`.
- 차단 요청: reason은 NotBlank·최대 500자, approvedBy는 NotBlank·최대 100자 선언. 해제 시에도 reason을 받지만 서비스에는 전달하지 않는다. 검증 오류의 공통 JSON 계약은 없다.

유의점:

- 현재 Spring Security/JWT/RBAC 구현이 없어 위 API의 인증·Admin 권한은 보장되지 않는다. 문구에 ‘관리자’가 있어도 권한 검사 구현과 같지 않다.
- 최신 지표의 404는 대상 자체 없음과 아직 지표 없음이 구별되지 않는다. 이력/최근 조회도 대상 존재 여부를 먼저 검사하지 않는다.
- Ping은 GET이지만 실제 접속을 수행하고 대상의 status·lastCheckedAt을 변경한다. BLOCKED 상태도 덮어쓸 수 있어 차단 상태와 분리해야 한다.
- 현재 날짜 타입은 LocalDateTime이다. RedisConfig의 직접 생성 ObjectMapper까지 있으므로 UTC 문자열 응답을 가정하지 말고 직렬화 샘플을 확인해야 한다.
- `/v3/api-docs`, `/swagger-ui.html` 경로가 설정되어 있다. 이번 문서 작업에서 실행 응답은 검증하지 않았다.
