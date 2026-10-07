# Part B 후속 런타임 수정 (2026-09-30)

기준: PR #11이 병합된 `develop` 커밋 `c31f041`에서 분기한 `feature/be-auth-runtime-fixes`.
PR #2의 인증·DB 관리, PR #11의 C lifecycle 호출은 이미 `develop`에 병합됐다.

## 이번 변경 체크리스트

- [x] `bootstrap-admin` 무웹 실행에서 서블릿 `SecurityConfig`를 생성하지 않도록 조건 추가.
- [x] DB 목록·상세 응답의 `createdAt`/`updatedAt` 및 `lastAttemptAt`/`lastSuccessAt`을 UTC `Instant` JSON (`.SSSZ`)으로 출력.
- [x] `TargetProvider.listEnabled()`를 비밀 없는 대상 목록으로 변경하고 대상별로 복호화. 한 대상의 복호화 실패가 다른 대상 수집을 막지 않음.
- [x] 복호화 실패 대상을 `PARTIAL_FAILURE`/`INTERNAL_ERROR` 메트릭·outbox로 기록. 비밀값은 로그/이벤트에 넣지 않음.
  (2026-10-02 A 후속: 접속 자체를 못 한 관측이므로 `CONNECTION_FAILED`/`INTERNAL_ERROR`로 변경, 표시 상태는 `DOWN`. [events.md](events.md) 참고)
- [x] 표시용 연결 상태는 성공 `UP`, 접속 실패 `DOWN`, 부분 실패 `UNKNOWN`으로 구분.
- [x] 전체 테스트 결과를 아래에 기입.
- [ ] 실제 Docker 통합 실행 결과를 아래에 기입.
- [x] 새 PR #12를 `develop` 대상으로 제출.
- [ ] A/C가 변경된 `TargetProvider`·수집 경계를 검토하고 병합.

## 아직 남은 통합 작업과 이유

- [x] C V4가 `develop`에 병합되면 `DatabaseConfigService`의 선택적 `MonitoringLifecyclePort` 호출을 필수 의존성으로 바꾸고, 등록·수정·삭제 롤백 테스트를 운영 빈으로 재실행한다. (반영됨: `MonitoringLifecyclePort`가 필수 의존성)
- [x] C의 `monitoring_states`가 준비되면 DB 응답의 상태 필드를 그 테이블에서 읽도록 전환한다. (2026-10-07 반영: `RISK_ENABLED=true`이면 `DatabaseDisplayStatusReader`가 `monitoring_states`의 connectionStatus·lastAttemptAt·lastSuccessAt을 읽고 A는 `database_configs` 표시 컬럼을 갱신하지 않는다. `RISK_ENABLED=false`이면 `monitoring_states`가 갱신되지 않으므로 기존처럼 A가 갱신하는 `database_configs`를 읽는다.)
- [x] `database_configs`의 레거시 `TIMESTAMP WITHOUT TIME ZONE`을 `TIMESTAMPTZ`로 이전한다. (2026-10-07 반영: V7, V3와 같은 `LEGACY_TIME_ZONE` 규칙. 기존 행이 있으면 기록 당시 JVM 시간대를 `LEGACY_TIME_ZONE`으로 지정해야 하며, 레거시 `blocked_reasons`도 함께 변환한다.)
- [ ] PostgreSQL·Redis·MariaDB를 함께 띄워 로그인 → DB 등록 → Ping → 수집 → 자격증명 실패 격리 → 복구를 확인한다. 자동 테스트의 내장 PostgreSQL은 운영형 3종 통합 실행을 대체하지 않는다.
- [ ] 실제 보존 대상 DB가 있다면 V2 백업·암호화 이전·복구 절차를 해당 데이터에서 검증한다. 신규 테스트 DB에서의 Flyway 통과와 구분한다.

## 검증 결과

2026-09-30 `backend/gradlew.bat test --no-daemon`: `BUILD SUCCESSFUL`, 99개 테스트,
실패 0·오류 0·건너뜀 0. 신규 테스트에는 무웹 보안 조건, 실제 HTTP DB 시간 문자열,
복호화 대상 격리, 실패 메트릭/outbox 및 `UNKNOWN` 표시 상태가 포함된다.
`git diff --check` 통과.

이 PC에서는 Docker CLI가 확인되지 않아 PostgreSQL·Redis·MariaDB의 Docker Compose
통합 실행은 아직 수행하지 않았다. 내장 PostgreSQL/Flyway 테스트 통과와 구분한다.
