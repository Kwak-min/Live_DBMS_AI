# A 통합 시나리오 실행 결과

- 실행: 2026-10-01T02:48:05.084Z ~ 2026-10-01T02:50:16.664Z (UTC)
- 기준 SHA: `c8ccc45`
- 환경: docker-compose(PostgreSQL 16, Redis 7.4, MariaDB 10.11) + 로컬 백엔드, 수집 주기 5초
- 대상: 시나리오 전용 MariaDB 계정으로 등록한 `scn-a-1001024804`(id=5), `scn-empty-1001024804`(id=6)

| ID | 결과 | 확인 항목 | 보류 |
| --- | --- | --- | --- |
| T06 | PASS | 6/6 | - |
| T09 | PASS | 11/11 | - |
| T10 | PASS | 4/4 | - |
| T11 | PASS | 9/9 | - |
| T07 | PASS | 8/8 | - |
| T08 | PASS | 7/7 | - |
| T13 | PASS | 7/7 | - |
| T26 | PASS (일부 보류) | 2/2 | 오래된 OPEN 사건·근거 값 보존은 C의 incidents(V4) 구현 후 확인 |

## T06 DB 등록 후 암호문 저장·비밀 미노출·A 접속 성공

- PASS 등록 201 — `201`
- PASS 응답에 username/password 필드 없음 — `['configVersion', 'connectionStatus', 'createdAt', 'databaseName', 'enabled', 'host', 'id', 'lastAttemptAt', 'lastSuccessAt', 'name', 'port', 'updatedAt']`
- PASS PostgreSQL에 암호문만 저장 — `ciphertext=t, plaintextInRow=False`
- PASS 암호문에 평문 바이트 없음 — `user=t, password=t`
- PASS A 수집기가 복호화 계정으로 접속 성공(SUCCESS 스냅샷) — `latest=200 SUCCESS`
- PASS API·이벤트·Redis·감사/접속 로그·앱 로그에 비밀 없음 — `0 hits`
- 증거: T06-01-create.json, T06-02-db-row.json, T06-03-first-metric.json, T06-04-secret-scan.json

## T09 최신 스냅샷 없음·대상 없음·빈 기간·잘못된 기간

- PASS 수집 중단 상태로 등록 201 — `201`
- PASS 스냅샷 없음 → 204 빈 본문 — `204`
- PASS recent 빈 결과 → 200 [] — `200 []`
- PASS 빈 기간 history → 200 [] — `200 []`
- PASS 대상 없음 latest → 404 DATABASE_NOT_FOUND — `404 DATABASE_NOT_FOUND`
- PASS 대상 없음 recent → 404 DATABASE_NOT_FOUND — `404 DATABASE_NOT_FOUND`
- PASS 잘못된 기간(start-equals-end) → 400 INVALID_TIME_RANGE — `400 INVALID_TIME_RANGE`
- PASS 잘못된 기간(over-24h) → 400 INVALID_TIME_RANGE — `400 INVALID_TIME_RANGE`
- PASS 잘못된 기간(non-utc) → 400 VALIDATION_ERROR — `400 VALIDATION_ERROR`
- PASS 잘못된 기간(missing-end) → 400 VALIDATION_ERROR — `400 VALIDATION_ERROR`
- PASS recent limit=0 → 400 — `400 VALIDATION_ERROR`
- 증거: T09-01-create-paused.json, T09-02-latest-no-snapshot.json, T09-03-recent-empty.json, T09-04-history-empty.json, T09-05-latest-not-found.json, T09-05-recent-not-found.json, T09-06-history-start-equals-end.json, T09-07-history-over-24h.json, T09-08-history-non-utc.json, T09-09-history-missing-end.json, T09-10-recent-limit-0.json

## T10 history [t0, t2) 반개구간과 정렬

- PASS 연속 스냅샷 3건 확보 — `3`
- PASS [t0, t2) 는 t0·t1 만 반환 — `t0=2026-10-01T02:48:09.786Z t1=2026-10-01T02:48:14.783Z t2=2026-10-01T02:48:19.784Z ids=[1110, 1114]`
- PASS timestamp, id 오름차순 — `[('2026-10-01T02:48:09.786Z', 1110), ('2026-10-01T02:48:14.783Z', 1114)]`
- PASS [t1, t2) 는 t1 하나(시작 포함·끝 제외) — `[1114]`
- 증거: T10-01-history-t0-t2.json, T10-02-history-t1-only.json

## T11 첫 수집 warmup·정상 0·카운터 초기화·접속 오류

- PASS 첫 수집은 SUCCESS, qps null, unavailable=WARMUP — `{'id': '1110', 'configVersion': '1', 'timestamp': '2026-10-01T02:48:09.786Z', 'status': 'SUCCESS', 'errorCode': '', 'qps': '', 'qpsUnavailable': 'WARMUP', 'slowQueries': '0', 'slowQueriesDelta': '', 'activeConnections': '3'}`
- PASS 두 번째 수집은 qps 계산 — `{'id': '1114', 'configVersion': '1', 'timestamp': '2026-10-01T02:48:14.783Z', 'status': 'SUCCESS', 'errorCode': '', 'qps': '3.603', 'qpsUnavailable': '', 'slowQueries': '0', 'slowQueriesDelta': '0', 'activeConnections': '3'}`
- PASS 실제 0 은 0 으로 유지(null·unavailable 아님) — `{'id': '1114', 'configVersion': '1', 'timestamp': '2026-10-01T02:48:14.783Z', 'status': 'SUCCESS', 'errorCode': '', 'qps': '3.603', 'qpsUnavailable': '', 'slowQueries': '0', 'slowQueriesDelta': '0', 'activeConnections': '3'}`
- PASS 인증 실패 → CONNECTION_FAILED / AUTH_FAILED, 모든 값 null — `{'id': '1137', 'configVersion': '4', 'timestamp': '2026-10-01T02:48:59.773Z', 'status': 'CONNECTION_FAILED', 'errorCode': 'AUTH_FAILED', 'qps': '', 'qpsUnavailable': 'COLLECTION_FAILED', 'slowQueries': '', 'slowQueriesDelta': '', 'activeConnections': ''}`
- PASS 인증 복구 후 첫 성공은 WARMUP — `{'id': '1140', 'configVersion': '4', 'timestamp': '2026-10-01T02:49:04.783Z', 'status': 'SUCCESS', 'errorCode': '', 'qps': '', 'qpsUnavailable': 'WARMUP', 'slowQueries': '0', 'slowQueriesDelta': '', 'activeConnections': '3'}`
- PASS 대상 중지 → CONNECTION_FAILED / CONNECTION_REFUSED·CONNECT_TIMEOUT — `{'id': '1144', 'configVersion': '4', 'timestamp': '2026-10-01T02:49:09.778Z', 'status': 'CONNECTION_FAILED', 'errorCode': 'CONNECTION_REFUSED', 'qps': '', 'qpsUnavailable': 'COLLECTION_FAILED', 'slowQueries': '', 'slowQueriesDelta': '', 'activeConnections': ''}`
- PASS 대상 복구 후 첫 성공은 WARMUP — `{'id': '1147', 'configVersion': '4', 'timestamp': '2026-10-01T02:49:14.773Z', 'status': 'SUCCESS', 'errorCode': '', 'qps': '', 'qpsUnavailable': 'WARMUP', 'slowQueries': '0', 'slowQueriesDelta': '', 'activeConnections': '3'}`
- PASS 재시작 뒤 첫 성공의 qps는 null(COUNTER_RESET 또는 실패 후 WARMUP), 음수 없음 — `COUNTER_RESET (사이 실패 0건)`
- PASS 전 구간 음수 qps·slowQueriesDelta 없음 — `0`
- 참고: 필수 조회 SQL 오류(PARTIAL_FAILURE/QUERY_FAILED)는 실제 MariaDB에서 재현하기 어려워 MetricSnapshotCalculatorTest로 검증한다.
- 증거: T11-01-recent-after-register.json, T11-02-latest-connection-failed.json, T11-03-target-metrics.json

## T07 configVersion 경쟁·수집 중 변경

- PASS 다른 configVersion PATCH → 409 — `409 CONFIG_VERSION_CONFLICT`
- PASS 현재 configVersion PATCH → 200, 버전 증가 — `200 2`
- PASS 변경 후 이전 버전 수집 결과 저장 0건 — `stale rows after change=0`
- PASS 새 버전 첫 수집은 WARMUP(이전 기준과 비교 안 함) — `{'id': '1121', 'configVersion': '2', 'timestamp': '2026-10-01T02:48:29.778Z', 'status': 'SUCCESS', 'errorCode': '', 'qps': '', 'qpsUnavailable': 'WARMUP', 'slowQueries': '0', 'slowQueriesDelta': '', 'activeConnections': '3'}`
- PASS 수집기가 최신 이름·버전을 덮어쓰지 않음 — `name=scn-a-1001024804-renamed configVersion=2 status=UP`
- PASS 수집 중 삭제 → 204 — `204`
- PASS 삭제 뒤 수집 결과 저장 0건 — `rows after delete=0`
- PASS 삭제 대상 latest → 404 — `404 DATABASE_NOT_FOUND`
- 증거: T07-01-patch-stale-version.json, T07-02-patch-name.json, T07-03-get-after-collection.json, T07-04-db-observation.json, T07-05-delete.json, T07-06-latest-after-delete.json

## T08 비활성 대상 Ping·활성화

- PASS 수집 중단 PATCH 200 — `200`
- PASS 중단 후 정기 수집 결과 저장 0건 — `rows after pause=0`
- PASS 중단 대상 Ping → 200 UP — `200 UP version=10.11.19-MariaDB-ubu2204`
- PASS 10초 내 재Ping → 429 RATE_LIMITED — `429`
- PASS Ping은 정기 상태·메트릭·이벤트를 바꾸지 않음 — `state same=True, metrics 7->7, outbox 7->7`
- PASS 활성화 PATCH 200 — `200`
- PASS 활성화 다음 주기에 수집 재개 — `1.2s 후 SUCCESS qpsUnavailable=WARMUP`
- 증거: T08-01-pause.json, T08-02-ping-paused.json, T08-03-ping-rate-limited.json, T08-04-state-before-after-ping.json, T08-05-resume.json

## T13 PostgreSQL 성공 뒤 Redis 실패·publisher 재시작

- PASS Redis 중단 중에도 메트릭은 PostgreSQL에 저장 — `rows=3`
- PASS 발행 실패 이벤트가 outbox에 미발행으로 보존 — `pending=3`
- PASS Redis 실패를 성공으로 처리하지 않음(attempts·lastError·backoff 기록) — `{'eventId': '73bbda70-c6a0-4e78-be89-665bdd0cefa3', 'eventType': 'MetricCollectedEvent', 'attempts': '3', 'lastError': 'QueryTimeoutException: Redis command timed out', 'nextAttemptAt': '2026-10-01T02:49:41.820Z'}`
- PASS outbox metricId 가 실제 metric_data 행과 일치 — `3/3`
- PASS 백엔드 재시작 후 health 200 — `True`
- PASS Redis 복구 후 보존된 이벤트 전부 발행 — `published`
- PASS 같은 eventId 로 재발행, metricId 가 DB·이벤트에서 일치 — `found=3/3, metricId mismatch=0, duplicates=0`
- 참고: Redis 중단 중 APP_RESTART_CMD로 백엔드를 재시작했다.
- 증거: T13-01-outbox-while-redis-down.json, T13-02-redis-after-recovery.json

## T26 보관 경계 직전/직후 자료

- PASS 경계 직전(만료) 메트릭 삭제 — `deleted`
- PASS 경계 직후 메트릭과 최근 메트릭 보존 — `boundary-after=1, recent=19`
- 참고: 보관 30일 경계 기준 5분 전(id=1167, 2026-09-01T02:44:56.608Z)·5분 후(id=1168, 2026-09-01T02:54:56.608Z) 행 삽입
- 증거: T26-01-retention.json
