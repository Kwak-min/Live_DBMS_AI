# 인증·권한·보안 규격 초안 v0.2

[전체 기준](integration-contract-draft.md) / [API](api.md) / [운영](integration-operations.md). **아래는 B가 구현하고 A/C/프론트가 공통으로 사용할 개발 기준**이다. 현재 코드에 이미 적용된 보안으로 해석하지 않는다.

## 1. 계정·역할

- 일반 가입은 USER, 최초 Admin은 bootstrap 전용 실행 프로필에서 생성한다. `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_DISPLAY_NAME`을 환경으로 주입하고, 사용자 테이블이 비어 있을 때만 생성한다. 실행 후 종료하며 HTTP 서버를 열지 않는다. 계정이 있으면 생성 없이 실패하고 운영자에게 이유를 반환한다.
- 사용자 비밀번호: 12~128 Unicode code point, UTF-8 최대 1024 byte. 공백도 원문 그대로 해시하며 trim/절단/문자 정규화 금지. DB 접속 비밀번호와 규칙을 혼용하지 않는다.
- 저장 해시: Argon2id, memory 19456KiB, iterations 2, parallelism 1, salt 16byte, hash 32byte. 해시 문자열에 알고리즘·파라미터를 저장한다. 해시/솔트는 응답하지 않는다. 이 초기값은 [OWASP 권고](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)를 따른다.
- email 정규화 값에 unique 제약. 사용자 활성 상태와 authVersion(초기 1)을 저장한다. 권한/활성 상태 변경은 authVersion 증가와 전체 세션 폐기를 같은 트랜잭션으로 처리한다.
- USER/ADMIN 모두 모든 DB·메트릭·사건·상태 조회 가능. DB 변경/Ping/정책/사용자/공용 Webhook/감사 조회는 ADMIN. Push는 본인 소유만 조작 가능하다.

## 2. Access JWT

| 항목 | 값 |
| --- | --- |
| 알고리즘 | HS256만 허용. alg=none 또는 다른 alg 거절 |
| 키 | `JWT_SIGNING_KEYS`의 kid→base64 secret 맵, 디코딩 32byte 이상; `JWT_ACTIVE_KID`로 서명 |
| iss / aud | live-dbms-ai / live-dbms-web |
| sub / role | 사용자 Id의 10진 문자열 / USER 또는 ADMIN |
| sid / ver | 서버 auth_sessions UUID / 사용자의 authVersion 정수 |
| iat / exp / jti | UTC epoch seconds / iat+900 / UUID |
| 검증 | 서명·kid·iss·aud·sub·role·sid·ver·iat·exp 모두 검증. exp 이후 즉시 만료; iat의 미래 허용 오차 최대 30초 |

B의 `AuthService.authenticate(accessToken)`이 DB에서 현재 사용자 활성/역할/authVersion과 세션 폐기·만료를 확인한다. JWT claim만으로 권한을 신뢰하지 않는다. A/C는 직접 비밀키를 해석하거나 다른 검증기를 만들지 않는다.

세션이 유효하면 `{userId,role,sessionId,expiresAt}` AuthPrincipal을 돌려준다. 토큰 누락/만료/변조/세션 폐기 오류는 api.md 코드를 사용한다. 비밀번호·토큰 전체·서명키를 로그에 기록하지 않는다. 키 교체 시 새 kid로 발급하고 구 키 검증은 15분+30초 뒤 제거한다. 키 값은 JWT/암호화/CSRF 용도 간 재사용하지 않는다.

## 3. Refresh 세션과 로그아웃

- Refresh는 JWT가 아닌 32byte 암호학적 난수의 base64url 문자열(패딩 없음)이다. 원문은 쿠키로만 보내고 DB에는 SHA-256 hash를 저장한다.
- auth_sessions는 sid, userId, currentRefreshHash, createdAt, expiresAt, revokedAt, authVersion을 가진다. expiresAt은 로그인 후 7일의 절대 만료이며 Refresh할 때 연장하지 않는다.
- 유효 Refresh를 row lock으로 검증한 뒤 새 난수를 발급하고 이전 hash를 used_refresh_tokens에 보관한다. 회전과 새 Access 발급의 기준 상태는 같은 트랜잭션이다. 사용된 Refresh 재사용은 해당 sid 전체를 폐기하고 401 REFRESH_TOKEN_INVALID로 처리한다.
- 요청에 Refresh가 없거나 알 수 없는 hash면 401; cookie 삭제. 로그아웃은 있으면 sid를 폐기하고 해당 세션의 Push 구독을 비활성화한 뒤 두 인증 쿠키를 제거한다. 이미 만료/폐기/쿠키 없음은 204다. CSRF/Origin 검사는 여전히 필요하다.
- Access 검증에서 sid를 확인하므로 로그아웃 직후 기존 Access도 SESSION_REVOKED다. C의 해당 sid 소켓도 종료한다. Refresh 회전만으로 이미 발급한 유효 Access를 폐기하지는 않는다.
- 다중 탭은 같은 origin의 Web Locks 이름 `live-dbms-refresh`로 Refresh를 직렬화하고, 탭 내부는 single-flight로 통합한다. BroadcastChannel `live-dbms-auth`로 갱신 Access/만료·로그아웃을 공유하되 영구 저장소에 토큰을 쓰지 않는다. 잠금 미지원 브라우저는 지원 대상에서 제외한다.
- 갱신 요청 중 네트워크 응답 유실로 회전 결과를 받지 못한 경우 Refresh POST를 자동 재전송하지 않는다. 로그인 화면으로 이동한다. 이미 사용된 Refresh를 허용하는 유예창은 두지 않는다.

### 쿠키 속성

| 쿠키 | HttpOnly | Secure | SameSite | Path | 수명 |
| --- | --- | --- | --- | --- | --- |
| refreshToken | true | 운영 true / local HTTP false | Lax | /api/v1/auth | 세션 절대 만료까지 남은 초 |
| csrfSession | true | 운영 true / local HTTP false | Lax | /api/v1/auth | 8시간 |

Domain 미지정(host-only), 제거 시 같은 Path/Domain 속성에 Max-Age=0. 프론트는 인증 요청에 credentials=same-origin을 사용한다. 운영은 단일 origin이고 cross-origin credential 인증은 지원하지 않는다. local은 `http://localhost:5173`의 개발 프록시가 `/api`와 `/ws`를 8080으로 전달한다. `127.0.0.1`과 `localhost`를 혼용하지 않는다.

## 4. CSRF·Origin

로그인·가입·Refresh·로그아웃은 쿠키가 없어도 CSRF 보호 대상이다. Bearer 전용 REST는 쿠키를 무시하므로 CSRF 토큰을 별도로 요구하지 않는다.

1. 프론트는 최초에 `GET /api/v1/auth/csrf`를 호출한다. B는 csrfSession 32byte 난수 쿠키와 별도의 csrfToken 32byte 난수를 만들고 Redis에 SHA-256(session)→token, TTL 8시간으로 저장한다. CSRF 원문은 이 내부 저장소와 해당 브라우저 응답에만 존재하고 로그에 남기지 않는다. 유효한 session의 GET은 기존 토큰을 반환하여 다른 탭의 토큰을 무효화하지 않는다.
2. 변경 인증 요청에 `X-CSRF-Token`으로 받은 토큰을 전달한다. 쿠키·헤더의 존재와 서버 토큰과의 일치 여부를 constant-time으로 검증한다. 이 토큰은 메모리 보관만 허용한다.
3. 인증 POST는 Origin이 `PUBLIC_ORIGIN`과 정확히 같아야 한다. Origin이 없으면 Referer의 origin이 정확히 같아야 하고 둘 다 없으면 403 ORIGIN_NOT_ALLOWED. CLI 검수는 Origin을 명시한다.
4. CSRF GET은 Origin이 있으면 같은 검사를 하고, 없을 때 Sec-Fetch-Site=cross-site 요청을 거절한다. 응답은 no-store이고 외부 origin에 CORS를 허용하지 않는다.
5. Redis 장애는 보호를 생략하지 않고 503을 반환한다. 토큰 만료는 403 CSRF_INVALID, 프론트가 csrf GET을 1회 재호출한 뒤 원래 인증 요청을 한 번만 재시도한다.
6. 로그아웃 후 두 쿠키를 지우므로 새 로그인 전에 csrf GET부터 다시 시작한다. 다른 탭은 auth 채널의 로그인/로그아웃 알림을 받아 메모리 상태를 갱신한다.

서버 상태를 사용하는 이 CSRF 방식과 Origin 검증은 [OWASP CSRF 가이드](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)의 synchronizer token 원칙을 적용한 팀 규격이다.

## 5. REST·STOMP 공통 인가

- REST는 필터→공통 AuthService→컨트롤러 권한 검사 순서. 인증 실패의 응답 DTO도 공통 형식이다.
- 소켓은 허용 Origin handshake 후 CONNECT native Authorization을 검증한다. CONNECT 없이 SUBSCRIBE 금지. URL query token, STOMP login/passcode 인증, 인증 쿠키 대체는 금지한다.
- C는 Spring ChannelInterceptor에서 Principal을 등록하고 모든 SUBSCRIBE에서 대상 존재/삭제와 현재 사용자 활성/역할을 재검사한다. 10초마다 세션을 재검증하고 JWT exp 시각에는 즉시 닫는다. 세션 폐기 이벤트가 있으면 10초를 기다리지 않고 종료한다.
- topic wildcard 구독은 거절하고 정수 ID의 정확한 목적지만 허용한다. 클라이언트 SEND/임의 user destination 지정은 금지한다. 같은 계정 최대 소켓 5개, 소켓당 최대 61개 구독(대상 20×3+errors 1), 프레임 최대 64KiB다.
- Actuator health는 단순 UP/DOWN만 공개, 상세 health/info/metrics와 Swagger/OpenAPI는 local 공개·운영 ADMIN 전용이다. DB/Redis 주소는 외부 health 응답에 포함하지 않는다.

## 6. 접속 정보 암호화

B가 MariaDB username/password 및 Slack URL, Web Push endpoint/keys를 암호화한다. host/port/name/databaseName은 비밀이 아닌 관리 메타데이터이며 공개 DTO의 범위대로 저장한다.

| 항목 | 저장 규칙 |
| --- | --- |
| 알고리즘 | AES-256-GCM, 32byte key, 매 암호화마다 새로운 12byte nonce, 16byte tag |
| 키 구성 | `DB_CONFIG_ENCRYPTION_KEYS`: keyVersion→base64 key 맵, `DB_CONFIG_ACTIVE_KEY_VERSION` 지정 |
| 암호문 포맷 | keyVersion 정수 + nonce bytea + ciphertextWithTag bytea; 텍스트 직렬화 시 각각 base64 |
| AAD | UTF-8 `<resourceType>:<resourceId>:<fieldName>`; 예: database:12:password |
| 길이 | 입력 byte 길이 + tag 16byte; 암호문을 varchar(255)에 저장하지 않음 |
| 키 교체 | 새 쓰기는 활성 키, 기존은 원래 keyVersion으로 읽기. 행 단위 재암호화 후 남은 구 버전이 0임을 확인하고 구 키 제거 |

접속 정보는 B의 내부 TargetProvider만 복호화하며 A의 JDBC 연결 범위에서만 사용한다. 일반 REST/Redis/DTO의 toString/예외에 포함하지 않는다. 다른 파트가 DatabaseConfig.password를 직접 읽는 경로는 제거한다. 복호화할 수 없는 대상 credential은 수집 대상별 `collectionStatus=CONNECTION_FAILED`와 `errorCode=INTERNAL_ERROR`로 기록한다. 연결을 시작하지 못해 측정할 시도가 없으면 `responseTimeMs=0`이며 상태는 `DOWN`, 원인은 `CONNECTION_FAILURE`로 공개한다. 관리자 로그에는 대상 ID와 keyVersion만 남기고 credential·암호문·예외 원문은 남기지 않는다.

## 7. 서버가 접속하는 주소와 로그

- MariaDB: host/port를 별도 입력받고 JDBC URI 파라미터를 사용자에게 받지 않는다. 등록 시와 매 접속 시 DNS를 해석하여 모든 후보 IP가 `TARGET_DB_ALLOWED_CIDRS`에 속하는지 검사한다. 연결은 검증한 IP로 고정하고 원래 호스트의 TLS 이름을 검증한다. `TARGET_DB_ALLOWED_PORTS` 기본 운영 3306, local 3306/13306. 허용 CIDR 미설정이면 등록/접속을 거절한다.
- 운영 MariaDB 연결은 TLS 인증서·호스트 검증을 켠다. 로컬 테스트 컨테이너만 local 프로필에서 비TLS를 허용한다. 수집 계정은 SELECT 및 상태 조회에 필요한 최소 권한, 변경 SQL 권한을 부여하지 않는다.
- Slack URL은 `https://hooks.slack.com/services/<세 경로 요소>`만 허용, userinfo/query/fragment/임의 포트 금지. redirect 금지, 요청 시간 제한 5초.
- Push endpoint는 HTTPS/443, userinfo/fragment 금지, redirect 금지. 기본 허용 host는 fcm.googleapis.com, updates.push.services.mozilla.com, web.push.apple.com, notify.windows.com의 하위 도메인. suffix 비교는 점 경계로 수행한다. 실제 연결 시 public IP만 허용하며 loopback/private/link-local/메타데이터 주소를 거절한다. 새 공급자 지원은 배포 설정의 명시적 허용 목록 변경으로만 추가한다.
- Push endpoint query strings are opaque provider data and remain allowed after the HTTPS/443, host, public-address, redirect, and timeout checks. Slack Incoming Webhook URLs reject query strings, fragments, userinfo, non-default ports, and path deviations. A provider/mobile or real-device acceptance result is outside this backend handoff.
- X-Forwarded-For는 TCP 직전 주소가 `TRUSTED_PROXY_CIDRS`인 경우에만 사용하고 오른쪽부터 trusted hop을 제거한 첫 미신뢰 IP를 사용한다. 그 외에는 remoteAddr. Proxy-Client-IP 등 기존 대체 헤더는 무시한다.
- 감사 action은 USER_SIGNUP, USER_LOGIN, USER_LOGOUT, USER_ROLE_CHANGED, USER_STATUS_CHANGED, DATABASE_CREATED, DATABASE_UPDATED, DATABASE_DELETED, DATABASE_PING, POLICY_UPDATED, PUSH_REGISTERED, PUSH_DELETED, WEBHOOK_CREATED, WEBHOOK_UPDATED, WEBHOOK_DELETED다. 실패도 result=FAILURE로 기록한다. Refresh 성공은 감사 대신 보안 세션 회전 기록에 남긴다.
- 성공한 관리 변경과 감사는 같은 PostgreSQL 트랜잭션이다. 감사 실패면 변경도 롤백한다. 거절/실패 감사는 원래 변경 트랜잭션이 롤백된 뒤 별도 트랜잭션으로 기록하여 실패 기록까지 사라지지 않게 한다. 일반 GET access log 저장 실패는 응답을 실패시키지 않고 구조화 운영 로그에 경고한다.
- Login: 정규화 email별 15분 10회 + IP별 15분 50회; Signup: IP별 시간당 10회; Refresh: sid별 분당 30회. Redis 원자 카운터로 제한하고 만료를 설정한다. 제한 저장소 장애 시 인증 변경 요청은 503이다.
