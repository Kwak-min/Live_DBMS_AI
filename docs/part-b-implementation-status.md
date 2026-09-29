# Part B 개발 체크리스트·미진행 사유·현재 상황 브리핑

작성 기준: 2026-09-29 `origin/develop` `c897c87` 위로 rebase한 `feature/be-auth` 로컬 작업 트리. 이 문서는 기능 완료 선언만을
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
- [x] 기존 평문 DB 자격증명에서 암호문으로 바꾸는 Flyway V2 코드와 실행 runbook 작성 (실제 운영형 PostgreSQL 실행 검증은 아래 미완료)
- [x] OpenAPI Bearer JWT 보안 스키마, Part B API별 권한·CSRF·주요 성공/오류 상태 및 DTO 필드 설명 추가

### 검증 완료

- [x] 기존 기준 단위·웹 슬라이스 테스트 62개 통과 (2026-09-29). 아래 최신 `develop` 기준 전체 64개 테스트가 이를 대체한다.
- [x] `git diff --check` 통과
- [x] Auth, DB CRUD/Ping, 감사 로그의 규격 경로·HTTP 메서드 대조 완료
- [x] Part B 변경부의 TODO/FIXME, 평문 비밀값 노출, 오류 응답·권한 표기 정적 점검 완료
- [x] A의 V1·마이그레이션 테스트 수정이 포함된 `develop` `c897c87` 위로 rebase 완료. `application.yml` 충돌을 `ddl-auto: validate`로 해결하고 중복 Flyway 설정·의존성을 제거. `git diff --check` 통과.
- [x] rebase 후 일반 PowerShell에서 `backend/gradlew.bat test --no-daemon` 성공 (2026-09-29, `BUILD SUCCESSFUL`). XML 결과 25개 스위트, 64개 테스트, 실패 0·오류 0·건너뜀 0. `MigrationSchemaTest` 2개도 통과: 새 DB 전체 migration 후 엔티티 `validate`, V1 기존 데이터의 V2 이전 후 `validate`.

## 2. 진행하지 못한 작업 — 사유와 후속 협의

아래는 검수 기준상 아직 완료하지 못한 작업이다. Part B가 직접 끝낼 항목과
공용 데이터·인프라 또는 다른 파트의 소유 영역이 필요한 통합 단계를 구분한다.

### Part B 명세 검증 대기

- [ ] `/v3/api-docs`를 실제 서버에서 export하여 Part B 필드·성공/오류 본문이 생성 명세와 일치하는지 대조한다.
  - 이유: 코드 어노테이션은 추가했으나 PostgreSQL/Redis 없는 환경에서 서버를 기동하여 생성 명세와 실제 HTTP 요청·응답을 검증하지 못했다. INT-01의 실행 증거는 아직 없다.
### A + B 공용 인프라·DB 검증 필요

- [ ] 기존 평문 컬럼이 `NOT NULL`인 PostgreSQL 사본에서 V2를 실행하고 이전 행·암호문·접속 로그 시간 변환을 검증한다.
  - 이유: 정적 점검에서 발견한 `NOT NULL` 충돌을 수정했고 A는 V1 위 V2 적용·validate 성공을 보고했다. 그러나 Part B가 보존할 데이터가 있는 실제 DB 사본에서 직접 검증한 증거는 아직 없다.
- [ ] PostgreSQL에 A의 V1을 먼저 적용하고 백업을 검증한 뒤 B V2를 실행한다.
  - 이유: A의 V1은 `develop`에 병합됐지만 이 항목은 실제 대상 DB에서 백업·적용을 확인하는 작업이다. V2는 기존 `database_configs` 평문 자격증명을 암호화하고 컬럼을 제거한다.
  - 담당/협의: A + B
- [ ] PostgreSQL·Redis·테스트 MariaDB가 준비된 환경에서 로그인 → DB 등록 → Ping → 수집 연결을 실행한다.
  - 현재 상태: 로컬 5432, 6379, 3306, 13306 포트 모두 비실행.
  - 담당/협의: A + B
- [ ] 운영 전 실제 암호화 키·CIDR·TLS와 DB 백업·마이그레이션 계획을 적용한다.
  - 현재 `application.yml` 기본값은 A 기준의 Flyway 활성화, `baseline-on-migrate: false`, `ddl-auto: validate`다. 기존 `ddl-auto: update`로 만든 로컬 DB는 별도 재생성 또는 백업 후 명시적 baseline 절차가 필요하다.
  - 담당/협의: A + B

### B + C 연동 협의 필요

C의 PR #3은 규격·샘플을 `develop`에 반영한 것이며, 아래 실제 연동 구현 완료를 뜻하지 않는다. `LifecyclePort` 및 `TargetChange` DTO의 제공 시점은 C와 계속 확인한다.

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

- [x] 2026-09-29 fetch한 `origin/develop` `c897c87` 기준 변경 확인: C 규격 PR #3, A V1 PR #4, A 테스트 수정 PR #5 반영.
- [x] 최신 `develop` 기준 전체 테스트 64개 재실행 및 통과 (위 검증 결과).
- [x] Part B 코드·테스트·문서를 `feature/be-auth` 로컬 브랜치에 커밋
- [x] GitHub `feature/be-auth`로 최초 Push 및 `develop` 대상 Draft PR #2 생성.
- [x] 최신 `develop` 위로 로컬 rebase 완료 (`47d67c8`). 설정 충돌 및 중복 정리, 작업 트리 clean 확인.
- [x] rebase 후 전체 테스트·마이그레이션 테스트 재실행 및 결과 기록 (위 64개 통과).
- [x] 재작성된 브랜치를 원격 커밋 `0934d31` 확인 후 명시적 `--force-with-lease`로 Push (`3f30c01`). PR #2의 대상 `develop`, 2개 커밋, 충돌 없음 확인.
- [x] PR #2 본문을 64개 테스트와 남은 통합 항목으로 갱신하고 Draft를 해제해 Ready for review로 전환. 병합은 A 검토 대기.
- [ ] PR 검토 과정에서 OpenAPI export·실제 HTTP 응답 대조 결과를 보완
- [ ] 병합/배포 전 V2 migration backup/rollback 및 실제 DB 암호문 검증
- [ ] 병합/배포 전 A/C 통합 시나리오 결과를 이 문서에 실행일·SHA·증거 경로와 함께 추가

## 현재 제한

Codex 실행 환경의 `JAVA_HOME`은 현재 존재하지 않는 JDK 경로를 가리켜 Gradle이 시작되지 않았지만,
사용자의 일반 PowerShell에서는 rebase 후 `gradlew.bat test --no-daemon` 전체가 성공했다.
`MigrationSchemaTest`는 내장 PostgreSQL을 사용한다. 실제 운영형 PostgreSQL의 V2 적용·백업·복구와 Redis·MariaDB 연동 검증은 별도다.

## 3. 현재까지 상황 브리핑

- 작업 브랜치: `feature/be-auth`
- 2026-09-29 fetch한 `origin/develop`: `c897c87` (A V1·테스트 수정, C 규격 반영). Part B 원격 브랜치 마지막 Push 커밋: `3f30c01`.
- Part B의 주요 코드·OpenAPI 어노테이션·정적 점검은 완료했지만 OpenAPI 실제 생성/응답 대조, 실제 V2 실행, C lifecycle 연결, 공용 시간 DTO 전환은 아직 미완료다.
- 현재 A 수집 스케줄러에는 `DatabaseConfig` 전체 저장·위험도 판단·자동 차단 호출이 남아 있어 v0.2 통합 규격과 맞지 않는다. A 소유 변경으로 별도 협의가 필요하다.
- PR #2는 Ready for review 상태이며, Part B 코드·테스트·실행 runbook·현황 문서를 포함한다. 이 문서의 PR 상태 후속 수정은 다음 Push 전까지 GitHub에 반영되지 않는다.
- A의 코드 리뷰와 병합 판단을 기다린다. 64개 테스트 통과가 OpenAPI 실제 생성/응답 대조·운영형 DB의 V2·A/C 통합 검증 완료를 뜻하지는 않는다.
- 공용 인프라가 준비되면 2번의 미진행 체크 항목을 실행 결과와 함께 갱신한다.
