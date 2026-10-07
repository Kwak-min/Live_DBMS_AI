# 프론트 연동 가이드

백엔드는 프론트 연동만 남은 상태다. 이 문서는 **화면별로 무엇을 어떤 순서로 부르는지**를 정리하고, 필드·오류 코드의 세부는 아래 규격으로 연결한다. 로컬 실행은 [로컬 실행 가이드](local-run-guide.md)를 따른다.

| 규격 | 내용 |
| --- | --- |
| [api.md](api.md) | REST 전 경로, DTO 필드, 오류 코드 |
| [events.md](events.md) · [part-c-realtime.md](part-c-realtime.md) | STOMP 목적지·메시지·재연결/대조 규칙 |
| [integration-security.md](integration-security.md) | 로그인·CSRF·Refresh·다중 탭 |
| [part-c-risk-notifications.md](part-c-risk-notifications.md) | Web Push 구독 계약 |
| [ai-insights.md](ai-insights.md) | AI 보고서 API |
| Swagger UI | 로컬 `http://127.0.0.1:8080/swagger-ui.html`, OpenAPI `/v3/api-docs` |

실제 백엔드로 이 흐름 전체를 확인한 기록은 [evidence/frontend-smoke/2026-10-07/summary.json](evidence/frontend-smoke/2026-10-07/summary.json)이다(34/34, [scripts/frontend-contract-smoke.py](../scripts/frontend-contract-smoke.py)). 프론트를 붙이기 전에 같은 스크립트로 로컬 백엔드를 점검할 수 있다.

## 1. 공통 규칙

- **같은 origin.** 브라우저는 `http://localhost:5173`만 쓰고 Vite proxy로 `/api`, `/ws`를 8080에 넘긴다(`changeOrigin: false`, `/ws`는 `ws: true`). `127.0.0.1:5173`과 섞지 않는다. 운영도 단일 HTTPS origin이다.
- **시각.** 모든 시각은 UTC `yyyy-MM-ddTHH:mm:ss.SSSZ` 문자열이다. 화면에서만 현지 시간으로 바꾼다. 조회 파라미터(`start`/`end`)도 같은 형식이다.
- **ID.** 숫자 ID는 JavaScript 안전 정수 범위다. 사건 ID는 UUID 문자열이다.
- **null은 0이 아니다.** 지표가 null이면 `unavailableMetrics`에 이유(`WARMUP`, `UNSUPPORTED`, `QUERY_FAILED` 등)가 있다. 0으로 채워 그리지 않는다. `cpuUsage`·`memoryUsage`는 v1에서 항상 null(`UNSUPPORTED`).
- **오류.** 모든 4xx/5xx 본문은 `{code, message, requestId, fieldErrors[]}`다. 분기는 `code`로 한다. `message`는 사용자에게 보여 줘도 된다.

| code | 프론트 처리 |
| --- | --- |
| `ACCESS_TOKEN_EXPIRED` | Refresh 1회 후 원래 요청 1회 재시도 |
| `AUTH_REQUIRED`·`INVALID_TOKEN`·`SESSION_REVOKED`·`REFRESH_TOKEN_INVALID` | 메모리 토큰 삭제 후 로그인 화면 |
| `CSRF_INVALID` | `GET /auth/csrf` 다시 받고 인증 요청 1회 재시도 |
| `FORBIDDEN` | 권한 없음 표시(USER에게는 변경 버튼을 숨김) |
| `CONFIG_VERSION_CONFLICT`·`POLICY_VERSION_CONFLICT` | 최신 값을 다시 읽고 사용자에게 다시 저장하게 함(자동 재전송 금지) |
| `RATE_LIMITED` | `Retry-After` 초 뒤에 다시 가능 |
| `DEPENDENCY_UNAVAILABLE` | 일시 장애 안내, 수동 재시도 |
| `VALIDATION_ERROR` | `fieldErrors`의 `field`별로 입력란에 표시 |

## 2. 로그인·세션 (모든 화면 공통)

1. 앱 시작 시 `GET /api/v1/auth/csrf` → `{csrfToken}`을 메모리에 둔다(쿠키 `csrfSession`은 브라우저가 관리).
2. 로그인 `POST /auth/login` + 헤더 `X-CSRF-Token` → `{accessToken, expiresIn:900, user}`. Access는 **메모리에만** 둔다. Refresh는 HttpOnly 쿠키라 JS가 보지 않는다.
3. 이후 REST는 `Authorization: Bearer <accessToken>`. Bearer 요청에는 CSRF가 필요 없다.
4. `ACCESS_TOKEN_EXPIRED`면 `POST /auth/refresh`(CSRF 헤더, 본문 없음). 여러 탭은 Web Locks `live-dbms-refresh`로 한 번만 갱신하고 BroadcastChannel `live-dbms-auth`로 새 토큰을 나눈다. 같은 Refresh를 두 번 쓰면 서버가 세션 전체를 폐기한다(실측 확인).
5. 로그아웃 `POST /auth/logout`(CSRF) → 204. 이 세션의 STOMP 연결은 서버가 즉시 끊는다. 이후 다시 1번부터.
6. 가입 `POST /auth/signup`은 201 User만 돌려주고 로그인시키지 않는다. 비밀번호는 8~128자, 앞뒤 공백도 그대로 쓴다.
7. `GET /auth/me`로 새로고침 후 사용자·역할을 복구한다(먼저 Refresh로 Access를 받아야 한다).

## 3. 화면별 호출

| 화면 | 진입 시 | 동작 | 권한 |
| --- | --- | --- | --- |
| DB 목록 | `GET /databases?page&size&enabled` | 추가 `POST /databases`, 수정 `PATCH /databases/{id}`(`configVersion` 필수, 바꾼 필드만), 삭제 `DELETE`, 연결 확인 `POST /databases/{id}/ping`(대상당 10초 1회) | 조회 USER/ADMIN, 변경 ADMIN |
| 대시보드(대상 1개) | STOMP 구독 먼저 → `GET /metrics/{id}/latest`(없으면 204), `GET /metrics/{id}/recent?limit=60`, `GET /databases/{id}/status` | 차트 구간 `GET /metrics/{id}/history?start&end`(최대 24시간, 오래된 순) | USER/ADMIN |
| 사건 | `GET /incidents?databaseConfigId&start&end&severity&status&page&size`(기본 최근 24시간, 최대 30일) | 상세 `GET /incidents/{incidentId}`. 지금 열린 사건 전체는 `/status`의 `openIncidentIds` | USER/ADMIN |
| 위험 정책 | `GET /databases/{id}/risk-policy` | 저장 `PUT` 전체 본문 + `version`(다르면 409) | 조회 USER/ADMIN, 저장 ADMIN |
| 알림(본인) | `GET /notifications/push-config`, `GET /notifications/push-subscriptions` | 구독 `POST`, 해제 `DELETE /notifications/push-subscriptions/{id}` | USER/ADMIN(본인 것만) |
| 알림(공용) | `GET /notifications/webhooks`, `GET /notifications/deliveries` | Slack 추가 `POST`, 수정 `PATCH`, 삭제 `DELETE` | ADMIN |
| 사용자 관리 | `GET /users` | `PATCH /users/{id}/role`, `PATCH /users/{id}/status`(마지막 ADMIN은 409 `LAST_ADMIN`) | ADMIN |
| 감사·접속 이력 | `GET /audit-logs`, `GET /access-logs` | 필터·페이지 | ADMIN |
| AI 보고서 | `GET /ai/status`(`available=false`면 생성 버튼 숨김), `GET /ai/reports?databaseConfigId&type` | 생성 `POST /databases/{id}/ai/daily-report?date`, `POST /databases/{id}/ai/query-analysis` → 202 PENDING, `GET /ai/reports/{reportId}`를 2~3초마다 확인 | 조회 USER/ADMIN, 생성 ADMIN |

- 목록 응답은 `{items, page, size, totalElements, totalPages}`이다.
- DB 응답에는 계정·비밀번호가 없다. 수정 화면의 계정·비밀번호 칸은 비워 두고, 사용자가 새로 입력했을 때만 보낸다.
- DB 목록의 `connectionStatus`·`lastAttemptAt`·`lastSuccessAt`은 `RISK_ENABLED=true`이면 `/status`와 같은 값이다.
- `dataFreshness`: `FRESH`(정상 수집), `STALE`(수집 끊김), `NO_DATA`(아직 첫 수집 전), `PAUSED`(일시 중지, `riskLevel=null`).
- 누적 `slowQueries`와 초당 `slowQueriesPerSecond`를 구분해 라벨을 붙인다.

## 4. 실시간(STOMP)

- 주소 `ws://localhost:5173/ws`(proxy 경유). CONNECT 헤더는 정확히 `accept-version:1.2`, `heart-beat:10000,10000`, `Authorization:Bearer <accessToken>`. 다른 heart-beat 값이나 query token은 거절된다.
- **클라이언트도 heart-beat를 보내야 한다.** 10초 약속을 지키지 않으면 서버가 연결을 끊는다. `@stomp/stompjs`처럼 heart-beat를 자동 처리하는 라이브러리를 쓰고 `heartbeatIncoming/Outgoing=10000`으로 맞춘다.
- 구독 순서: `/user/queue/errors` → `/topic/databases/{id}/metrics` · `/status` · `/incidents`. 와일드카드·SEND는 금지다. 계정당 소켓 5개, 소켓당 구독 61개.
- 메시지는 `{schemaVersion:1, eventId, eventType, databaseConfigId, publishedAt, data}`. `eventType`은 `MetricUpdated`(data=Metric), `MonitoringStatusChanged`(data=StatusSnapshot), `IncidentCreatedEvent`·`IncidentUpdatedEvent`·`IncidentResolvedEvent`(data=Incident).
- 프레임은 갱신 신호일 뿐 보장된 재전송이 아니다. `eventId`로 중복을 버리고, metric은 `(configVersion, timestamp, data.id)`, status는 `stateVersion`, 사건은 `incidentVersion`으로 최신만 적용한다.
- 진입·재연결 흐름: 구독 → 프레임을 버퍼링하며 REST 기준선 조회 → 기준선보다 새 프레임만 적용 → 2초 뒤 한 번 더 조회 → 이후 30초마다 대조. 끊긴 동안의 차트 구간은 `history`로 채운다.
- 로그아웃·권한 변경·비활성화 시 서버가 `ERROR`(`SESSION_REVOKED`)를 보내고 바로 닫는다. 이때 재연결하지 말고 로그인 상태를 확인한다.
- 일시 중지·재개·삭제는 `/status`로 바로 반영된다(실측: PATCH 뒤 1초 안에 `PAUSED` 프레임).

## 5. Web Push

- 사용자 동작(버튼 클릭) 안에서 서비스 워커 등록 → `Notification.requestPermission()` → `GET /notifications/push-config`의 `publicKey`로 `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})` → 결과를 그대로 `POST /notifications/push-subscriptions`.
- 본문은 `{endpoint, expirationTime, keys:{p256dh, auth}}`이고 `expirationTime`은 null이어도 반드시 넣는다. 응답은 `{id, createdAt, updatedAt, expirationTime}`만 돌아온다.
- 권한 거부는 "미구독"으로 표시한다. 로그아웃하면 서버가 그 세션에서 만든 구독을 해제하므로 다시 로그인하면 다시 등록한다.
- 알림 클릭 시 payload의 `url`(`/incidents/<UUID>`)로 같은 origin에서 이동한다. 로그인 전이면 로그인 후 그 경로로 돌아간다.
- `push-config`가 503이면 서버에 VAPID 키가 없다는 뜻이다([공유 환경 설정](shared-environment.md)).
- 서비스 워커·Push는 HTTPS 또는 `localhost`에서만 동작한다. 휴대폰 확인은 HTTPS 주소가 필요하다.

## 6. 기능 스위치가 화면에 주는 영향

| 서버 설정 | 꺼져 있을 때 화면 |
| --- | --- |
| `RISK_ENABLED` | `/status`는 응답하지만 `riskLevel`·사건이 갱신되지 않는다. DB 목록 상태는 수집기 기준 |
| `REALTIME_ENABLED` | `/ws` 연결이 안 된다. REST 주기 조회로 대체 |
| `NOTIFICATIONS_ENABLED` | 구독·Webhook 등록은 되지만 발송되지 않는다 |
| `AI_ENABLED`·키 | `/ai/status.available=false`, 생성 요청 503 `AI_UNAVAILABLE`. 저장된 보고서 조회는 가능 |

공유 환경에서 무엇을 켜야 하는지는 [공유 환경 설정](shared-environment.md)에 있다.
