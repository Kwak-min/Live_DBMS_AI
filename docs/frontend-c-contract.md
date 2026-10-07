# 프론트 Part C 연동 계약

기준: #28이 병합된 `0a07ac6` + FCM 수정 `0ac113e`. 전체 API 필드는 [api.md](api.md), 샘플은 [contract-examples/part-c.json](contract-examples/part-c.json), 이벤트 상세는 [events.md](events.md)를 따른다. 프론트 구현·제품 화면 검수는 별도다.

공유 환경에서 `REALTIME_ENABLED=true`, `NOTIFICATIONS_ENABLED=true`여야 소켓·외부 알림이 동작하고, 위험도·사건 생성은 `RISK_ENABLED=true`로 활성화한다. 세 flag는 기본 false다. 환경 준비는 A가 조율한다.

## STOMP 연결과 인증

브라우저 origin과 백엔드 `PUBLIC_ORIGIN`을 정확히 맞춘다. 개발은 `http://localhost:5173`의 proxy로 `/api`와 `/ws`를 전달하며 `/ws`는 `ws: true`, `changeOrigin: false`로 둔다. 포트가 다르면 환경변수를 바꾼다. 운영은 단일 HTTPS origin과 `wss`다.

Native WebSocket `/ws`를 사용한다. SockJS와 URL query token은 사용하지 않는다. CONNECT 헤더는 아래 세 개이며 Access token은 메모리에 보관한다.

```text
accept-version:1.2
heart-beat:10000,10000
Authorization:Bearer <accessToken>
```

클라이언트 heartbeat 수신·송신은 각각 10,000ms다. CONNECT 제한은 5초, 최대 프레임은 64KiB, 계정당 소켓은 5개, 소켓당 구독은 오류 큐 포함 61개다. 쿠키는 CONNECT Bearer 대체 수단이 아니다. SEND와 wildcard 구독은 금지다.

## 토픽과 이벤트

먼저 `/user/queue/errors`를 구독하고 대상별 토픽을 등록한다.

| 토픽 | eventType | data |
| --- | --- | --- |
| `/topic/databases/{id}/metrics` | `MetricUpdated` | Metric |
| `/topic/databases/{id}/status` | `MonitoringStatusChanged` | StatusSnapshot |
| `/topic/databases/{id}/incidents` | `IncidentCreatedEvent`, `IncidentUpdatedEvent`, `IncidentResolvedEvent` | Incident |

세 토픽의 envelope는 `{schemaVersion:1,eventId,eventType,databaseConfigId,publishedAt,data}`다. UTC 시각은 밀리초 세 자리 `...SSSZ`이며 null 지표를 0으로 채우지 않는다. 오류 큐는 `Error` envelope이고 상세 필드는 [events.md](events.md)의 구독 오류 계약을 따른다. `/topic/metrics/{id}`, `/topic/incidents`, `/app/ping`은 계약에 없다.

`eventId`로 중복을 버린다. Metric은 `(configVersion,timestamp,data.id)`, 상태는 `(configVersion,stateVersion)`, 사건은 incidentId별 `incidentVersion`으로 최신만 반영한다. 세 종류의 커서를 분리한다.
동일 사건 버전의 REST 응답은 `lastObservedAt` 최댓값만 갱신할 수 있다. 삭제 상태 또는 status 404는 구독을 해제하고 목록을 다시 조회한다. SUBSCRIBE는 고유 id와 `ack:auto`를 사용하며 RECEIPT·개별 ACK에 의존하지 않는다.

## 재연결과 REST 대조

1. 연결·재연결마다 오류 큐와 대상 토픽을 재구독하고 프레임을 버퍼링한다.
2. `GET /api/v1/metrics/{id}/latest`, `/api/v1/databases/{id}/status`, 필요한 `/api/v1/incidents`를 조회해 기준선을 만든다. 최신 지표 204는 아직 데이터가 없다는 뜻이다.
3. 기준선보다 새 프레임만 적용하고 2초 뒤 한 번 더 조회한다. 이후 30초마다 REST와 대조한다. 끊긴 차트 구간은 `/api/v1/metrics/{id}/history?start&end`로 보충한다.
4. 네트워크 단절은 간격을 두고 재연결하되 각 CONNECT 직전에 최신 메모리 토큰을 넣는다. 재연결 지연은 1/2/4/8/16/30초에 0~20% jitter를 더하고, 연결이 30초 안정되면 backoff를 초기화한다.
5. `ACCESS_TOKEN_EXPIRED`면 B의 CSRF Refresh 절차를 한 번 수행한 뒤 새 토큰으로 새 소켓을 연결한다. 기존 소켓의 인증은 바꾸지 않는다. `SESSION_REVOKED`, `INVALID_TOKEN`, `AUTH_REQUIRED`면 무한 재연결을 중지하고 인증 상태를 확인한다.

로그아웃·사용자 역할 변경·비활성화는 세션 폐기로 소켓을 종료한다. 서버는 인증 상태를 10초마다 재검증하고 Access 만료 시 종료하며, 로컬 세션 폐기 이벤트는 즉시 종료를 요청한다. 프론트도 로그아웃 시 소켓을 닫고 재연결을 중지한다. 서버의 연결 종료와 HTTP 로그아웃 성공은 각각 확인한다.

## Push 구독 API

아래 API는 `Authorization: Bearer ...`가 필요하며 USER/ADMIN 자신의 구독만 다룬다.

| API | 결과 |
| --- | --- |
| `GET /api/v1/notifications/push-config` | 200 `{publicKey}`; 미설정이면 503 |
| `GET /api/v1/notifications/push-subscriptions` | 200 본인 활성 구독 **배열** |
| `POST /api/v1/notifications/push-subscriptions` | 신규 201, 기존 본인 구독 갱신 200 |
| `DELETE /api/v1/notifications/push-subscriptions/{id}` | 204; 본인 구독을 찾지 못하면 404 |

사용자 버튼 동작에서 SW 등록·알림 권한 요청 후 `publicKey`를 applicationServerKey로 변환해 `pushManager.subscribe({userVisibleOnly:true,applicationServerKey})`를 호출한다. 브라우저 구독 JSON `{endpoint,expirationTime,keys:{p256dh,auth}}`를 그대로 등록한다. `expirationTime`은 null이어도 반드시 포함한다. 응답은 `{id,createdAt,updatedAt,expirationTime}`이며 endpoint와 비밀 키를 반환하지 않는다. FCM 경로 변환은 서버에서 수행하므로 프론트가 endpoint를 고치지 않는다.

거부·미지원은 미구독 상태로 표시한다. 구독은 인증 세션에 연결되므로 로그아웃·세션 폐기 후 다시 로그인하면 기존 브라우저 구독을 서버에 다시 등록한다. 해제 버튼은 서버 DELETE와 브라우저 unsubscribe를 처리한다. HTTPS 또는 localhost에서 검수한다.

## 알림 payload와 클릭

Push JSON은 아래 필드만 사용한다. STOMP envelope와 다른 형식이다.

```json
{
  "schemaVersion": 1,
  "deliveryId": 81,
  "incidentId": "d38f135a-34c3-40df-915a-f26b2ebf4162",
  "type": "INCIDENT_OPENED",
  "title": "TEST ONLY",
  "body": "Synthetic incident",
  "url": "/incidents/d38f135a-34c3-40df-915a-f26b2ebf4162",
  "tag": "incident:d38f135a-34c3-40df-915a-f26b2ebf4162",
  "sentAt": "2026-10-03T01:02:03.456Z"
}
```

`type`은 `INCIDENT_OPENED`, `SEVERITY_INCREASED`, `INCIDENT_RESOLVED`다. SW `push`에서 `event.waitUntil(showNotification(...))`로 알림을 표시하고 `url`을 notification data에 보관한다. `notificationclick`에서 같은 origin의 `/incidents/{UUID}`로 기존 창을 이동하거나 새 창을 연다. 로그인 필요 시 사건 경로를 보관하고 로그인 후 복귀한다.

제품 검수는 발생·복구 각각의 provider 수락, SW 수신, 화면 알림, 클릭 후 실제 사건 상세 렌더링을 구분해 남긴다. 이미 성공한 임시 QA SW 결과를 제품 SW 검수 완료로 쓰지 않는다.
