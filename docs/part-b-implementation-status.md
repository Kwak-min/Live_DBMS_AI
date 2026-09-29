# Part B 개발 체크리스트·미진행 사유·현재 상황 브리핑

작성 기준: `feature/be-auth` 로컬 작업 트리. 이 문서는 기능 완료 선언만을
의미하지 않으며, 실제 공용 인프라와 A/C 통합이 필요한 항목을 명확히 구분한다.

## 1. Part B 담당 작업 체크리스트 — 완료

- [x] USER/ADMIN 계정, 회원가입, 로그인, `/auth/me`, 사용자 역할·활성 상태 관리
- [x] Argon2id 비밀번호 해시와 email 정규화·고유 제약
- [x] HS256 Access JWT, Refresh 난수 토큰, 회전, 재사용 시 세션 폐기, 로그아웃
- [x] CSRF synchronizer token, Origin/Referer 검증, 인증·일반 API Redis 제한
- [x] REST Bearer 인증·RBAC, C가 재사용할 STOMP Access token 인증 어댑터
- [x] DB 설정 CRUD, optimistic `configVersion`, soft delete, 20개 제한, Admin 전용 변경
- [x] MariaDB username/password AES-256-GCM 암호화, 키 버전, AAD, 시작 시 복호화 검증
- [x] 대상 DB CIDR/포트/DNS 재검증, 고정 IP 연결, 운영 TLS 호스트 검증
- [x] Ping 제한·안전한 오류 코드, 사용자/DB 변경·Ping 감사 로그, access log 조회 API
- [x] trusted proxy X-Forwarded-For 처리, 보관 정리 스케줄, bootstrap-admin 무HTTP 프로필
- [x] 기존 평문 DB 자격증명에서 암호문으로 바꾸는 Flyway V2 코드와 실행 runbook 작성 (실제 PostgreSQL 실행 검증은 아래 미완료)
- [x] OpenAPI Bearer JWT 보안 스키마, Part B API별 권한·CSRF·주요 성공/오류 상태 및 DTO 필드 설명 추가

### 검증 완료

- [x] 단위·웹 슬라이스 테스트 62개 재실행 통과 (`failures=0`, `errors=0`, 2026-09-29). 컴파일 산출물 생성 후 Gradle의 컴파일 태스크를 제외해 실행했으며, 일반 `gradlew test` 성공을 뜻하지 않는다.
- [x] `git diff --check` 통과
- [x] Auth, DB CRUD/Ping, 감사 로그의 규격 경로·HTTP 메서드 대조 완료
- [x] Part B 변경부의 TODO/FIXME, 평문 비밀값 노출, 오류 응답·권한 표기 정적 점검 완료

## 2. 진행하지 못한 작업 — 사유와 후속 협의

아래는 검수 기준상 아직 완료하지 못한 작업이다. Part B가 직접 끝낼 항목과
공용 데이터·인프라 또는 다른 파트의 소유 영역이 필요한 통합 단계를 구분한다.

### Part B 명세 검증 대기

- [ ] `/v3/api-docs`를 실제 서버에서 export하여 Part B 필드·성공/오류 본문이 생성 명세와 일치하는지 대조한다.
  - 이유: 코드 어노테이션은 추가했으나 PostgreSQL/Redis 없는 환경에서 서버를 기동하여 생성 명세와 실제 HTTP 요청·응답을 검증하지 못했다. INT-01의 실행 증거는 아직 없다.
### A + B 공용 인프라·DB 협의 필요

- [ ] 기존 평문 컬럼이 `NOT NULL`인 PostgreSQL 사본에서 V2를 실행하고 이전 행·암호문·접속 로그 시간 변환을 검증한다.
  - 이유: 정적 점검에서 발견한 `NOT NULL` 충돌을 수정했지만, 실제 DB 실행 결과는 없다. A의 V1 스키마 또는 확인된 기존 스키마 사본이 필요하다.
- [ ] PostgreSQL에 A의 V1을 먼저 적용하고 백업을 검증한 뒤 B V2를 실행한다.
  - 이유: V2는 기존 `database_configs` 평문 자격증명을 암호화하고 컬럼을 제거한다.
  - 담당/협의: A + B
- [ ] PostgreSQL·Redis·테스트 MariaDB가 준비된 환경에서 로그인 → DB 등록 → Ping → 수집 연결을 실행한다.
  - 현재 상태: 로컬 5432, 6379, 3306, 13306 포트 모두 비실행.
  - 담당/협의: A + B
- [ ] 운영 전 `FLYWAY_ENABLED=true`, `HIBERNATE_DDL_AUTO=validate`, 실제 키·CIDR·TLS를 적용한다.
  - 이유: 저장소의 `false/update` 기본값은 A V1 반영 전 개발 전환값이다.
  - 담당/협의: A + B

### B + C 연동 협의 필요

- [ ] B의 DB 등록·수정·삭제 트랜잭션에서 C의 실제 `LifecyclePort`를 호출한다.
  - 이유: 현재 B CRUD에는 호출 지점이 없고 C의 포트·상태 테이블 구현도 없다. 팀 검수표는 이 연결 전에는 DB CRUD를 통합 완료로 처리하지 않는다.
- [ ] DB 설정 변경/비활성화/삭제 시 C가 기존 OPEN incident를 `CONFIG_CHANGED` 또는 `MONITORING_PAUSED`로 종료한다.
  - 이유: incident 상태·알림 정책은 C 소유 영역이다.
- [ ] 로그아웃·세션 폐기 시 C가 해당 STOMP 연결과 Push 구독을 종료/비활성화한다.
  - 이유: STOMP 연결·Push 구독 lifecycle은 C 소유 영역이다.
- [ ] C의 Slack Webhook·Web Push 비밀 컬럼은 B의 `ResourceSecretCrypto`를 이용해 AES-256-GCM으로 저장한다. C는 테이블·CRUD·전송 로직을 소유한다.
  - 이유: B는 암호화 도구를 제공했고, C가 비밀 데이터 모델과 발송 기능을 구현해야 한다.
- [ ] API 시간 타입의 UTC `Instant` 전환은 A의 기존 `LocalDateTime` DTO 변경과 한 PR에서 조율한다. B가 단독으로 공유 DTO를 변경하지 않는다.
  - 이유: A·C 및 클라이언트 응답 형식에 영향을 주는 공용 계약 변경이다.

## PR 제출 및 병합 전 절차

- [x] 원격 최신 SHA fetch 및 diff 확인: `origin/develop`은 `f774c8d`이고 현재 작업 시작점과 동일하다.
  - 원격에 현재 존재하는 브랜치는 `develop`, `feature/be-auth`뿐이며, 별도 `feature/be-collector`/`feature/be-notification` 원격 참조는 없다.
- [x] 최신 `develop` 기준 62개 테스트 재실행 (위 분리 실행 방식; 일반 Gradle 빌드는 캐시 접근 오류)
- [x] Part B 코드·테스트·문서를 `feature/be-auth` 로컬 브랜치에 커밋
- [ ] GitHub `feature/be-auth`로 Push 및 `develop` 대상 PR 생성
  - 현재 상태: 이 실행 환경에서 Git Credential Manager의 Windows 자격증명 저장소 접근이 실패하여 Push가 중단됨. GitHub 원격에는 아직 이번 커밋이 올라가지 않았다.
- [ ] PR 검토 과정에서 OpenAPI export·실제 HTTP 응답 대조와 일반 `gradlew test` 결과를 보완
- [ ] 병합/배포 전 V2 migration backup/rollback 및 실제 DB 암호문 검증
- [ ] 병합/배포 전 A/C 통합 시나리오 결과를 이 문서에 실행일·SHA·증거 경로와 함께 추가

## 현재 제한

이 실행 환경의 Gradle 캐시는 일부 JAR 접근 시 Windows `AccessDeniedException`을 낼 수 있다.
단위·웹 슬라이스 62개는 2026-09-29에 재실행해 통과했으나, PR 검토 중 일반 개발 환경에서
`./gradlew test` 전체 명령도 성공하는지 확인해야 한다. 실제 PostgreSQL V2·Redis·MariaDB 검증은 별도다.

## 3. 현재까지 상황 브리핑

- 작업 브랜치: `feature/be-auth`
- 최신 원격 `develop` 확인 SHA: `f774c8d` (현재 작업 시작점과 동일, 병합 충돌 없음)
- Part B의 주요 코드·OpenAPI 어노테이션·정적 점검은 완료했지만 OpenAPI 실제 생성/응답 대조, 실제 V2 실행, C lifecycle 연결, 공용 시간 DTO 전환은 아직 미완료다.
- 현재 A 수집 스케줄러에는 `DatabaseConfig` 전체 저장·위험도 판단·자동 차단 호출이 남아 있어 v0.2 통합 규격과 맞지 않는다. A 소유 변경으로 별도 협의가 필요하다.
- 이번 PR에는 Part B 코드, 테스트, 실행 runbook, 이 현황 문서를 함께 포함한다.
- PR은 현재 상태를 공개해 검토받을 수 있다. OpenAPI 실제 생성/응답 대조·일반 빌드·실제 V2와 A/C 통합 검증이 끝났다는 뜻으로 병합해서는 안 된다.
- 공용 인프라가 준비되면 2번의 미진행 체크 항목을 실행 결과와 함께 갱신한다.
