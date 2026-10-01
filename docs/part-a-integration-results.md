# A 담당 통합 시나리오 결과 (T06~T11, T13, T26)

[integration-handoff.md](integration-handoff.md) 5절의 A 범위 시나리오를 실제 로컬 환경에서 실행한 기록이다. 실행 날짜·기준 SHA·입력·관측 결과는 증거 디렉터리의 `summary.md`와 `results.json`에 있고, 요청·응답·DB/Redis 관측값은 시나리오별 JSON으로 남긴다.

## 1. 최신 실행

| 항목 | 값 |
| --- | --- |
| 실행 | 2026-10-01T02:48:05Z ~ 02:50:16Z (UTC) |
| 기준 SHA | `c8ccc45` (feature/be-collector, develop `8700db6` + A 수정) |
| 환경 | docker-compose PostgreSQL 16 · Redis 7.4 · MariaDB 10.11, 로컬 백엔드(local 프로필), 수집 주기 5초 |
| 증거 | [evidence/part-a/2026-10-01/summary.md](evidence/part-a/2026-10-01/summary.md) |

| ID | 결과 | 실제 환경에서 확인한 내용 | 자동 테스트 |
| --- | --- | --- | --- |
| T06 | PASS | 등록 응답에 계정 없음, `database_configs`에는 암호문만 있고 평문 바이트가 없음, 복호화한 계정으로 수집 SUCCESS, 응답·outbox·Redis 3개 stream·감사/접속 로그·앱 로그에서 계정명·비밀번호 0건 | `TargetDriverLoggingConfigTest`, B의 `DatabaseCredentialCryptoTest` |
| T07 | PASS | 다른 configVersion PATCH는 409 `CONFIG_VERSION_CONFLICT`, 수집 중 이름 변경 후 이전 버전 결과 0건·새 버전 첫 수집은 WARMUP·수집기가 이름/버전을 덮어쓰지 않음, 수집 중 삭제는 204이고 이후 저장 0건·latest 404 | `MetricCollectionRecorderIntegrationTest` |
| T08 | PASS | 수집 중단 뒤 정기 수집 0건, 중단 대상 Ping 200 UP, 10초 안의 재Ping은 429, Ping 전후 상태·메트릭·outbox 변화 없음, 활성화 후 다음 주기(약 1초 뒤)에 수집 재개 | `DatabasePingControllerTest` |
| T09 | PASS | 스냅샷 없음 204 빈 본문, recent/빈 기간 200 `[]`, 대상 없음 404 `DATABASE_NOT_FOUND`, 시작=끝·24시간 초과 400 `INVALID_TIME_RANGE`, 비UTC·누락 400 `VALIDATION_ERROR`, limit=0 400 | `MetricServiceIntegrationTest`, `MetricControllerTest` |
| T10 | PASS | 실제 연속 스냅샷 t0·t1·t2에서 `[t0, t2)`는 t0·t1, `[t1, t2)`는 t1만 반환, timestamp·id 오름차순 | `MetricServiceIntegrationTest` |
| T11 | PASS | 첫 수집 SUCCESS + qps null(WARMUP), 다음 수집 qps 계산, 실제 0은 0으로 유지, 인증 실패는 `CONNECTION_FAILED/AUTH_FAILED`, 대상 중지는 `CONNECTION_FAILED/CONNECTION_REFUSED`, 복구 뒤 WARMUP, MariaDB 재시작 뒤 `COUNTER_RESET`, 전 구간 음수 qps 없음 | `MetricSnapshotCalculatorTest`(QUERY_FAILED 포함) |
| T13 | PASS | Redis 중단 중에도 메트릭은 PostgreSQL에 저장, 이벤트는 미발행으로 남고 맨 앞 이벤트에 attempts·lastError 기록, Redis 중단 중 백엔드 재시작 → Redis 복구 뒤 전부 발행, stream의 eventId·metricId가 outbox·metric_data와 일치 | `OutboxPublisherIntegrationTest`, `OutboxPublisherTest` |
| T26 | PASS (일부 보류) | 30일 경계 5분 전 행 삭제, 5분 후 행과 최근 행 보존 | `MetricRetentionIntegrationTest`(경계 ±1ms·배치·삭제 대상) |

### 보류

- T26 "오래된 OPEN 사건·근거 값 보존"은 C의 `incidents`(V4) 구현 후 확인한다.
- T12·T16은 C의 소비자(`cg:risk`)·상태 판정이 있어야 해서 A 단독으로 실행하지 않았다.
- 필수 조회 SQL 오류(`PARTIAL_FAILURE/QUERY_FAILED`)는 실제 MariaDB에서 안정적으로 만들기 어려워 단위 테스트로만 확인했다.

## 2. 실행 중 발견·수정한 문제

- **T06: 앱 로그에 대상 DB 계정명이 노출됐다.** MariaDB 드라이버의 `org.mariadb.jdbc.message.server.ErrorPacket`가 인증 실패 시 `Access denied for user '<계정>'@...`를 WARN으로 남긴다. `application.yml`에서 이 로거를 `OFF`로 설정했다. 수집기와 Ping은 원래 errorCode·sqlState만 기록하므로 진단 정보는 그대로 남는다. 수정 후 다시 실행해 앱 로그 0건을 확인했다.

## 3. 다시 실행하는 법

```bash
docker compose up -d
# 백엔드: local 프로필 + T26 확인을 위해 보관 정리를 15초마다 실행
APP_METRICS_RETENTIONCLEANUPCRON='*/15 * * * * *' java -jar backend/build/libs/monitoring-backend-0.0.1-SNAPSHOT.jar

# 다른 터미널 (ADMIN 계정 필요)
export BOOTSTRAP_ADMIN_EMAIL=... BOOTSTRAP_ADMIN_PASSWORD=...
export APP_LOG=<백엔드 로그 파일>            # 선택: 로그 비밀 노출 검사
export APP_RESTART_CMD='<백엔드 재시작 명령>'  # 선택: T13 publisher 재시작
python scripts/part-a-scenarios.py            # 증거: docs/evidence/part-a/<UTC 날짜>/
```

- 스크립트는 시나리오 전용 MariaDB 계정과 대상 두 개를 만들고, 끝나면 계정과 빈 대상을 지운다. 시나리오 대상은 T07에서 삭제(soft delete)된다.
- 실행 중 수집 대상 MariaDB를 재시작·중지하고 Redis를 잠시 중지하므로 로컬에서만 실행한다.
- 증거에는 계정·비밀번호·토큰을 저장하지 않는다(요청 본문의 계정 필드는 `<redacted>`).
