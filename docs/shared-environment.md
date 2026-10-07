# 공유 환경 설정 (프론트 연동·시연용)

로컬 개발은 [로컬 실행 가이드](local-run-guide.md)를 따른다. 이 문서는 팀이 함께 쓰는 서버(프론트 연동·실기기 알림 확인·시연)를 띄울 때 넣을 설정을 모은다. 값 자체는 비밀이므로 저장소·문서·메신저에 올리지 않고 서버의 환경 변수(또는 비밀 관리 도구)에만 둔다.

## 1. 반드시 정할 것

| 항목 | 이유 |
| --- | --- |
| **HTTPS 주소 하나** (예: `https://dbms.example.com`) | 서비스 워커·Web Push는 HTTPS에서만 동작하고, 인증 쿠키·Origin 검사가 단일 origin을 전제로 한다 |
| 그 주소에서 `/api`, `/ws`를 백엔드(8080)로 넘기는 reverse proxy | 프론트와 API가 같은 origin이어야 한다. `/ws`는 WebSocket upgrade 허용 |
| proxy의 IP 대역 | `TRUSTED_PROXY_CIDRS`에 넣어야 감사 로그에 실제 사용자 IP가 남는다 |

## 2. 환경 변수

`backend/.env.example`에 전체 목록이 있다. 공유 환경에서 기본값과 다르게 넣어야 하는 것만 적는다.

| 변수 | 값 | 비고 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | (비움 = 운영 기본) | `local`은 Swagger 공개·비TLS 대상 DB 허용용이므로 공유 환경에서 쓰지 않는다 |
| `PUBLIC_ORIGIN` | `https://<공유 주소>` | 1절의 주소와 정확히 같게 |
| `AUTH_SECURE_COOKIES` | `true` | HTTPS 전용 쿠키 |
| `TRUSTED_PROXY_CIDRS` | proxy 대역 | 비우면 X-Forwarded-For를 무시한다 |
| `JWT_SIGNING_KEYS` / `JWT_ACTIVE_KID` | `{"<kid>":"<base64 32바이트 이상>"}` / kid | 로컬과 다른 새 키 |
| `DB_CONFIG_ENCRYPTION_KEYS` / `DB_CONFIG_ACTIVE_KEY_VERSION` | `{"1":"<base64 32바이트>"}` / `1` | **잃어버리면 등록한 대상 DB 계정을 복호화할 수 없다.** 안전한 곳에 보관 |
| `TARGET_DB_ALLOWED_CIDRS` / `TARGET_DB_ALLOWED_PORTS` | 모니터링할 MariaDB의 IP 대역 / `3306` | 비우면 대상 등록이 모두 거절된다 |
| `SPRING_DATASOURCE_*`, `SPRING_REDIS_*` | 공유 PostgreSQL 16·Redis 7 접속 정보 | |
| `LEGACY_TIME_ZONE` | 기존 데이터를 기록한 서버 시간대(예: `Asia/Seoul`) | 기존 DB를 이전할 때만 필요(V2·V3·V7) |
| `RISK_ENABLED` / `REALTIME_ENABLED` / `NOTIFICATIONS_ENABLED` | `true` | 위험도·사건, STOMP 실시간, 알림 발송 |
| `WEB_PUSH_VAPID_PUBLIC_KEY` / `WEB_PUSH_VAPID_PRIVATE_KEY` / `WEB_PUSH_VAPID_SUBJECT` | 3절 | 없으면 `push-config` 503 |
| `PUSH_ALLOWED_HOSTS` | (비움) | 기본 허용 공급자(FCM·Mozilla·Apple·Windows)로 충분하다 |
| `AI_ENABLED` / `GEMINI_API_KEY` | `true` / 키 | [ai-insights.md](ai-insights.md). 무료 등급은 입력이 Google 제품 개선에 쓰일 수 있다 |
| `APP_REDIS_STREAM_RETENTION_MAX_AGE_HOURS` | `168` | Redis 스트림 최대 보관 |

키 만들기(32바이트, base64):

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

## 3. VAPID 키 (Web Push)

```bash
npx web-push generate-vapid-keys
```

출력된 Public Key(base64url, 65바이트)와 Private Key(base64url, 32바이트)를 넣고, `WEB_PUSH_VAPID_SUBJECT`는 `mailto:<연락 이메일>` 또는 `https://<주소>`로 한다. 키를 바꾸면 기존 브라우저 구독은 다시 등록해야 한다.

## 4. 첫 실행 순서

1. PostgreSQL·Redis를 띄우고 백엔드를 한 번 `bootstrap-admin` 프로필로 실행해 첫 ADMIN을 만든다(`BOOTSTRAP_ADMIN_EMAIL`·`BOOTSTRAP_ADMIN_PASSWORD`·`BOOTSTRAP_ADMIN_DISPLAY_NAME`, 실행 후 변수 제거).
2. 백엔드를 일반 실행한다. Flyway가 V1~V7을 적용한다.
3. ADMIN으로 로그인해 대상 MariaDB를 등록하고, Slack Webhook(`POST /api/v1/notifications/webhooks`)을 등록한다.
4. 백엔드 서버에서 [scripts/frontend-contract-smoke.py](../scripts/frontend-contract-smoke.py)를 실행해 전 구간을 점검한다(8080 직접 접속, STOMP는 평문 ws로 붙는다).
5. 프론트를 같은 주소로 배포한다.

## 5. 점검 체크리스트

- [ ] `https://<주소>/actuator/health` → `{"status":"UP"}`
- [ ] 로그인 → DB 등록 → 5초 안에 `/metrics/{id}/latest` 200
- [ ] STOMP 연결 후 `/topic/databases/{id}/metrics` 프레임 수신
- [ ] `/notifications/push-config` 200(VAPID 설정 확인)
- [ ] 사건 발생 시 Slack 채널 수신, 복구 알림 수신
- [ ] 실기기(안드로이드 Chrome·iOS 홈 화면 앱)에서 Push 수신과 클릭 이동
- [ ] `/ai/status`의 `available=true`
