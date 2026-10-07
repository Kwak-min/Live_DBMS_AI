# 로컬 실행 가이드 (프론트 연동용)

백엔드를 내 PC에서 띄워 프론트(Vite, `http://localhost:5173`)를 실제 서버에 붙여 보기 위한 순서다. Windows PowerShell 기준이며 저장소 루트에서 실행한다. 설정의 근거와 운영 환경 값은 [integration-operations.md](integration-operations.md) 1절, 인증·쿠키 세부는 [backend/README.md](../backend/README.md)를 따른다.

## 0. 준비물

- JDK 17 (`java -version`이 `17.x`)
- Docker Desktop (실행 중)
- 포트 5432(PostgreSQL), 6379(Redis), 13306(대상 MariaDB), 8080(백엔드)이 비어 있을 것

## 1. 의존성 실행

```powershell
./scripts/start-local-services.ps1 -WithMariaDb
```

PostgreSQL 16 · Redis 7.4 · 대상 MariaDB 10.11이 `127.0.0.1`에만 열린다. 처음 실행하면 MariaDB에 수집용 계정 `monitor`/`monitor`와 샘플 DB `sample_app`이 만들어진다. 중지는 `./scripts/stop-local-services.ps1` (데이터 유지), 완전 초기화는 `docker compose down -v`.

## 2. 로컬 키 만들기 (처음 한 번)

환경 변수로 넣는다(`local` 프로필은 `backend/.env`도 읽지만, 아래 키는 저장소 밖 스크립트에 두는 것을 권장한다). 아래로 무작위 키 두 개를 만든다.

```powershell
function New-LocalKey { $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) }
"JWT key: $(New-LocalKey)"
"DB  key: $(New-LocalKey)"
```

출력된 값으로 **저장소 밖**(예: `$HOME\live-dbms-local.ps1`)에 다음 파일을 만든다. 이 파일은 커밋하거나 메신저로 공유하지 않는다.

```powershell
# $HOME\live-dbms-local.ps1 — 로컬 전용
$env:SPRING_PROFILES_ACTIVE       = 'local'
$env:JWT_SIGNING_KEYS             = '{"local-1":"<JWT key>"}'
$env:JWT_ACTIVE_KID               = 'local-1'
$env:DB_CONFIG_ENCRYPTION_KEYS    = '{"1":"<DB key>"}'
$env:DB_CONFIG_ACTIVE_KEY_VERSION = '1'
$env:PUBLIC_ORIGIN                = 'http://localhost:5173'
$env:AUTH_SECURE_COOKIES          = 'false'
$env:TARGET_DB_ALLOWED_CIDRS      = '127.0.0.1/32,::1/128'
$env:TARGET_DB_ALLOWED_PORTS      = '3306,13306'
$env:LEGACY_TIME_ZONE             = 'Asia/Seoul'
```

- PostgreSQL·Redis 접속 정보는 `local` 프로필 기본값(`postgres`/`postgres`, Redis 비밀번호 없음)을 쓰므로 넣지 않아도 된다.
- **DB key는 계속 같은 값을 써야 한다.** 등록한 대상 DB 계정이 이 키로 암호화되어 저장되므로, 키를 바꾸면 시작 시 복호화 검증(`DB_CONFIG_VERIFY_ON_STARTUP`)에 실패한다. 키를 잃어버렸다면 `docker compose down -v`로 DB를 초기화하고 처음부터 다시 한다.

## 3. 관리자 계정 만들기 (빈 DB에서 한 번)

저장소에 시드 계정은 없다. 일반 회원가입은 USER로만 만들어지므로 첫 ADMIN은 HTTP를 열지 않는 일회성 프로필로 만든다.

```powershell
. $HOME\live-dbms-local.ps1
$env:SPRING_PROFILES_ACTIVE        = 'local,bootstrap-admin'
$env:BOOTSTRAP_ADMIN_EMAIL         = 'admin@local.test'
$env:BOOTSTRAP_ADMIN_PASSWORD      = '<8~128자 비밀번호>'
$env:BOOTSTRAP_ADMIN_DISPLAY_NAME  = 'Local Admin'
cd backend; ./gradlew.bat bootRun --no-daemon; cd ..
Remove-Item Env:BOOTSTRAP_ADMIN_EMAIL, Env:BOOTSTRAP_ADMIN_PASSWORD, Env:BOOTSTRAP_ADMIN_DISPLAY_NAME
```

`Bootstrap administrator created successfully` 로그 후 프로세스가 스스로 종료되면 성공이다. 이미 bootstrap 관리자가 있는 DB에서는 두 번째 생성을 거부한다.

## 4. 백엔드 실행

새 PowerShell 창에서:

```powershell
. $HOME\live-dbms-local.ps1
cd backend; ./gradlew.bat bootRun --no-daemon
```

- `Started MonitoringApplication` 로그가 나오면 `http://127.0.0.1:8080`에서 동작한다. Flyway가 V1~V6을 자동 적용한다.
- 확인: `http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`, Swagger UI `http://127.0.0.1:8080/swagger-ui.html` (local 프로필만).
- 수집기는 기본으로 켜져 있고 5초마다 수집한다.

### 파트 C 기능 켜기 (선택)

위험도·사건(`StatusSnapshot.riskLevel`, 사건 목록)과 STOMP 실시간은 기본으로 꺼져 있다. 프론트에서 실제 값을 확인하려면 실행 전에 켠다.

```powershell
$env:RISK_ENABLED     = 'true'   # 상태·위험도·사건 판정
$env:REALTIME_ENABLED = 'true'   # /ws STOMP 구독
```

`NOTIFICATIONS_ENABLED`(Web Push·Slack 발송)는 VAPID 키 등 추가 설정이 필요하므로 [part-c-risk-notifications.md](part-c-risk-notifications.md)를 따른다.

### AI 기능 켜기 (선택)

일일 보고서·위험 쿼리 분석은 AI API 키가 있어야 생성된다. 기본 제공자는 Gemini이고 [Google AI Studio](https://aistudio.google.com)에서 무료로 키를 받을 수 있다. 키가 없어도 조회 API는 동작하고 생성 요청만 503 `AI_UNAVAILABLE`이다.

`backend/.env` 파일(커밋되지 않음, `.gitignore` 대상)에 넣으면 `local` 프로필이 자동으로 읽는다. 실제 환경 변수가 있으면 그쪽이 우선한다. `.env.example`에는 절대 실제 키를 넣지 않는다(커밋된다).

```
# backend/.env
AI_ENABLED=true
GEMINI_API_KEY=<발급받은 키>
```

로컬 MariaDB에서 위험 쿼리 분석의 누적 통계를 보려면 `performance_schema=ON`이 필요하다. 자세한 내용은 [ai-insights.md](ai-insights.md).

## 5. 프론트 연결

- Vite dev server가 `/api`와 `/ws`를 `http://127.0.0.1:8080`으로 proxy한다(`/ws`는 `ws: true`). 브라우저 코드에서 8080을 직접 호출하지 않는다. CORS가 아니라 same-origin proxy 기준이다.
- 브라우저 주소는 `http://localhost:5173`으로 통일한다(`127.0.0.1:5173`과 섞으면 Origin 검사·쿠키가 어긋난다).
- 로그인 흐름: `GET /api/v1/auth/csrf` → 응답의 `csrfToken`을 `X-CSRF-Token` 헤더로 `POST /api/v1/auth/login`. Refresh·CSRF 쿠키는 HttpOnly·`SameSite=Lax`·경로 `/api/v1/auth`이고, access token은 메모리에만 둔다.
- STOMP: `ws://localhost:5173/ws`, CONNECT 헤더 `Authorization: Bearer <accessToken>`. 프레임 예시는 [part-c-realtime.md](part-c-realtime.md).
- Mock 끄기: 프론트 프로젝트의 `VITE_USE_MOCK=false`.

```ts
// vite.config.ts 예시
server: {
  port: 5173,
  proxy: {
    '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    '/ws':  { target: 'http://127.0.0.1:8080', ws: true, changeOrigin: false },
  },
},
```

`changeOrigin: false`로 두어 백엔드가 브라우저의 `Origin: http://localhost:5173`을 그대로 받게 한다.

## 6. 대상 DB 등록과 수집 확인

ADMIN으로 로그인한 뒤 DB 관리 화면에서 다음 값으로 등록한다.

| 필드 | 값 |
| --- | --- |
| name | 아무 표시 이름 (예: `local-mariadb`) |
| host / port | `127.0.0.1` / `13306` |
| databaseName | `sample_app` |
| username / password | `monitor` / `monitor` |

5초 안에 첫 수집이 저장되고 `GET /api/v1/metrics/{id}/latest`가 200을 돌려준다. 첫 수집의 `qps` 등 증가율 지표는 `null`(`unavailableMetrics`에 `WARMUP`)이고 두 번째 수집부터 계산된다.

## 7. 응답에서 알아 둘 것

- 시각은 모두 UTC ISO 8601, 밀리초 3자리 + `Z` (예: `2026-10-01T02:48:09.786Z`).
- `cpuUsage`·`memoryUsage`는 MariaDB 쿼리로 호스트 자원을 얻을 수 없어 v1에서는 항상 `null`이고 `unavailableMetrics`에 `UNSUPPORTED`로 표시된다.
- 슬로우 쿼리 본문·프로세스 목록 조회 API는 없다. 슬로우 쿼리는 집계값(`slowQueries`, `slowQueriesDelta`, `slowQueriesPerSecond`)만 제공하고, 쿼리 단위 분석은 AI 위험 쿼리 분석(리터럴 제거 후)으로만 제공한다.
- 최신 스냅샷이 없으면 `latest`는 204(빈 본문), 대상이 없으면 404 `DATABASE_NOT_FOUND`.

## 8. 자주 막히는 곳

| 증상 | 원인·해결 |
| --- | --- |
| 시작 시 `JWT_SIGNING_KEYS is required` | 2절 파일을 현재 창에서 `. $HOME\live-dbms-local.ps1`로 불러오지 않았다 |
| 시작 시 대상 DB 자격 증명 복호화 실패 | DB key가 처음과 다르다. 원래 키를 쓰거나 `docker compose down -v` 후 다시 시작 |
| 로그인 403 | `X-CSRF-Token` 누락, 또는 브라우저 Origin이 `http://localhost:5173`이 아님 |
| DB 등록 시 주소·포트 거절 | `TARGET_DB_ALLOWED_CIDRS`/`TARGET_DB_ALLOWED_PORTS`가 2절 값과 다르다 |
| 사건·위험도가 비어 있음 | `RISK_ENABLED=true`로 실행하지 않았다 |
| STOMP 연결 거절 | `REALTIME_ENABLED=true` 누락, 또는 CONNECT에 Bearer 토큰 누락 |
| 5432/6379 포트 충돌 | 로컬에 설치된 PostgreSQL·Redis 서비스를 중지한다 |
