# B 담당 통합 시나리오 결과 (T01~T05, T27, T28)

[integration-handoff.md](integration-handoff.md) 5절의 B 범위 시나리오를 실제 로컬 환경에서 실행한 기록이다. 실행기는 [scripts/part-b-scenarios.py](../scripts/part-b-scenarios.py)이고, 시나리오별 요청·응답·DB 관측값은 증거 디렉터리의 JSON과 `summary.json`에 있다. 증거에는 토큰·비밀번호·서명 키·평문 계정이 없다(실행 후 검사).

## 1. 최신 실행

| 항목 | 값 |
| --- | --- |
| 실행 | 2026-10-07T12:43Z ~ 12:45Z (UTC) |
| 기준 SHA | `824f44a` (feature/backend-pre-frontend, develop `0cb5948` + V7·상태 표시 전환·STOMP 즉시 종료) |
| 환경 | docker-compose PostgreSQL 16 · Redis 7.4 · MariaDB 10.11(새 볼륨), 로컬 백엔드(local 프로필) `REALTIME_ENABLED=true`, `TRUSTED_PROXY_CIDRS` 비움 |
| 증거 | [evidence/part-b/2026-10-07/summary.json](evidence/part-b/2026-10-07/summary.json) |

| ID | 결과 | 실제 환경에서 확인한 내용 | 자동 테스트 |
| --- | --- | --- | --- |
| T01 | PASS | `role` 필드를 넣은 가입은 400이고 계정이 생기지 않음, 정상 가입은 201·role=USER·토큰 미발급, DB에는 `$argon2id$v=19$m=19456,t=2,p=1` 해시만 있고 평문 없음 | `PasswordHashingServiceTest`, `PartBHttpContractTest` |
| T02 | PASS | USER는 DB 목록 200, DB 생성 403 `FORBIDDEN`, 사용자 관리 403. ADMIN은 DB 생성 201 | `PartBHttpContractTest` |
| T03 | PASS | 같은 키로 만든 만료 토큰 401 `ACCESS_TOKEN_EXPIRED`, 서명 변조·alg=none 401 `INVALID_TOKEN`, claim만 ADMIN으로 바꾼 토큰은 DB 기준 재검증으로 거절. 로그아웃 뒤 같은 sid의 Access 401 `SESSION_REVOKED`. STOMP: 유효 토큰 CONNECTED, 로그아웃·관리자 비활성화 시 0.0초 안에 ERROR로 종료, 폐기된 토큰의 CONNECT 거절 | `AccessTokenServiceTest`, `StompSessionRevocationListenerTest` |
| T04 | PASS | Refresh 200·쿠키 회전, 이전 Refresh 재사용 401 `REFRESH_TOKEN_INVALID`와 sid 전체 폐기(회전으로 받은 Access도 `SESSION_REVOKED`), 같은 Refresh 동시 2건은 정확히 200 1건·401 1건 | `AuthenticationServiceTest`, `AuthRegistrationRevocationConcurrencyIntegrationTest` |
| T05 | PASS | CSRF 누락·변조 403 `CSRF_INVALID`, 다른 Origin·Origin/Referer 없음 403 `ORIGIN_NOT_ALLOWED`, Redis 중단 중 CSRF 발급·로그인 503 `DEPENDENCY_UNAVAILABLE`(보호 생략 없음), Redis 복구 뒤 정상 | `PartBHttpContractTest` |
| T27 | PASS | 신뢰 proxy가 없으면 `X-Forwarded-For`/`X-Real-IP`를 무시하고 감사 로그에 실제 접속 IP(127.0.0.1) 기록. 허용 밖 DB(사설 CIDR·메타데이터 169.254.169.254·포트 5432), Slack URL(다른 host·query·포트·http), Push endpoint(다른 host·http·loopback·접미사 위장) 모두 400 | `TargetAddressPolicyTest`, `SlackWebhookPolicyTest`, `PushEndpointPolicyTest` |
| T28 | PASS | V1 baseline DB(평문 계정·로컬 시각·BLOCKED)를 백엔드 1회 실행으로 V7까지 이전. 이전 전 pg_dump 백업과 복구 확인, 평문 컬럼 제거·모든 행 암호문 완성·DB 덤프에 평문 없음·이전 로그에 계정 없음, 행 수 보존(대상 3·메트릭 1·차단 이력 1·접속 로그 1), `LEGACY_TIME_ZONE=Asia/Seoul` 로컬 09:00 → UTC 00:00, BLOCKED·비활성 대상은 비활성·PAUSED(자동 활성화 없음) | `MigrationSchemaTest`(V3·V7 변환) |

### 이번 실행에서 고친 것

- **세션 폐기 시 STOMP 즉시 종료(T03).** 처음 실행에서 로그아웃 뒤 STOMP 연결이 0.7~9.1초 뒤에야 닫혔다(10초 주기 재검증). 규격은 폐기 이벤트가 있으면 바로 닫도록 한다. B의 세션 폐기 경로가 `AuthSessionsRevokedEvent`를 발행하고, C의 `StompSessionRevocationListener`가 커밋 직후 해당 sid·사용자 연결을 닫도록 연결했다(`824f44a`). 재실행에서 0.0초.
- 실행기 오류: 처음에는 STOMP CONNECT에 `heart-beat:0,0`을 보내 서버가 규격대로 `VALIDATION_ERROR`로 거절했다. 규격([part-c-realtime.md](part-c-realtime.md))대로 `heart-beat:10000,10000`으로 고쳤다.

### 범위 밖·보류

- 다중 탭 Refresh 직렬화(Web Locks·BroadcastChannel)는 프론트 담당이다. 서버는 같은 Refresh 동시 요청에서 한 건만 회전시키는 것까지 확인했다.
- 실제 Push 제공자·Slack 수신과 브라우저·모바일 확인은 C·프론트 범위다.

## 2. 다시 실행하는 방법

```bash
docker compose up -d
# 백엔드: local 프로필, REALTIME_ENABLED=true, TRUSTED_PROXY_CIDRS 비움
export BOOTSTRAP_ADMIN_EMAIL=... BOOTSTRAP_ADMIN_PASSWORD=...
export JWT_SIGNING_KEYS='...' JWT_ACTIVE_KID=...        # 백엔드와 같은 값(T03 만료 토큰)
export LEGACY_TIME_ZONE=Asia/Seoul DB_CONFIG_ENCRYPTION_KEYS='...' DB_CONFIG_ACTIVE_KEY_VERSION=1
export APP_MIGRATE_CMD='<DB 이름을 인자로 받아 백엔드를 local,bootstrap-admin 프로필로 한 번 실행하는 명령>'
python scripts/part-b-scenarios.py --out docs/evidence/part-b/<날짜>
```

T05는 Redis 컨테이너를 잠시 멈추고, T28은 PostgreSQL에 `legacy_t28_*` 검증 DB를 만든다(실행 후 남겨 둠). 로컬 환경에서만 실행한다.
