# 담당별 적용·통합 검수표 v0.2

[전체 기준](integration-contract-draft.md) / [REST](api.md) / [이벤트](events.md) / [보안](integration-security.md) / [저장·운영](integration-operations.md)

이 묶음은 팀장이 전달할 **구체적인 개발 기준 초안**이다. 담당자가 선택할 미결정 설계 항목 대신 적용할 규격·기본값·완료 조건을 적었다. 아래 체크는 실제 코드 적용 및 검수 결과가 생길 때만 완료한다. 이번 작업은 문서 작성이므로 기능 완료 체크를 하지 않는다.

## 1. 팀에 전달할 요약

> 이번 MVP는 Spring Boot 단일 애플리케이션으로 구성합니다. A는 수집·지표·발행 기반, B는 인증·DB 설정·보안·감사, C는 위험도·사건·실시간·알림을 담당합니다. REST는 /api/v1, 시간은 UTC 문자열, 대상 참조는 databaseConfigId로 통일합니다. 위험도는 C가 한 번만 판단하고 장애 중에도 수집을 유지합니다. 문서의 DTO·오류·권한·Redis/STOMP 규칙을 그대로 구현하고, 기존 코드와 다른 부분은 함께 제공한 전환 목록에 맞춰 수정해 주세요. 배포 주소·키·계정은 실제 환경 값으로 주입하며 코드/문서에 기록하지 않습니다.

## 2. 적용 순서와 선행 조건

| 순서 | 산출물 | 주관 | 다음 단계가 사용할 기준 |
| --- | --- | --- | --- |
| 1 | 패키지/공통 DTO·UTC Jackson·공통 오류·JDK/DB/Redis 실행 설정·Flyway V1~V4 파일 소유 등록 | A 조정, B 공통 API | 공통 타입·requestId·마이그레이션 순서 |
| 2 | AuthService/TargetProvider/AuditRecorder와 인증·DB CRUD·암호화 | B | A 수집 설정, C/프론트 공통 인증 |
| 3 | Metric DTO/원본 저장·outbox·5초 수집·메트릭 REST | A | C가 사용할 실제 성공·실패·warmup 이벤트 |
| 4 | C LifecyclePort와 기본 정책/상태 테이블, B CRUD 트랜잭션 연결 | B·C | 등록/변경/삭제가 상태와 원자적으로 연결 |
| 5 | C 위험도 소비·사건·복구·상태 이벤트·조회 API | C | 프론트 상태/이력과 알림 입력 |
| 6 | STOMP 인증·전송, Push/Slack 수신처·발송 작업 | C | 프론트 실시간·알림 구독 |
| 7 | 로그인/관리/대시보드 통합과 장애/재시작 시나리오 | 전원 | 요구사항별 수신 결과·증거 |

순서 2~4에서 인터페이스가 필요한 부분은 동일 DTO의 테스트 대역을 써서 병렬 개발한다. 대역은 운영 프로필에 포함하지 않는다. B의 DB CRUD를 통합 완료로 처리하려면 실제 C LifecyclePort까지 연결되어 있어야 한다. 구체적인 구현보다 계약 DTO와 샘플을 먼저 각 브랜치에 공유한다.

## 3. 담당별 수정·추가 목록

### A — 수집·메트릭·발행 기반

- [ ] MetricSchedulerWorker의 위험도/사건 발행/자동 차단 호출 제거. 대상별 중첩 수집 금지, 전체 15초 취소·연결 정리 구현.
- [ ] B TargetProvider로 암호화된 계정 접근, configVersion 재검증 후 저장, DatabaseConfig 전체 save 제거.
- [ ] LocalDateTime을 Instant/UTC JSON으로 전환, QPS warmup/reset null, slowQueriesDelta/slowQueriesPerSecond/window·lastSuccessAt·errorCode·unavailableMetrics 추가.
- [ ] metric+outbox 같은 트랜잭션, eventId 고정 재발행, Redis 실패를 성공으로 처리하지 않음.
- [ ] 메트릭 최신/최근/이력의 인증·204·404·범위/건수 제한·정렬·반개구간 적용.
- [ ] Ping의 새 POST 경로와 무상태 진단(정기 수집 상태 미변경), 수동 중단 대상 진단 허용.
- [ ] Flyway V1/V3·UTC 보관 정리·공통 outbox·환경 문서 구현. V2/V4 변경은 각 담당자와 통합.

### B — 사용자·DB 관리·공통 보안

- [ ] 공통 JWT/세션/Refresh 회전/CSRF/로그아웃·키 설정, REST/STOMP 공유 검증 서비스.
- [ ] USER/ADMIN 권한, 초기 Admin 생성, 마지막 활성 Admin 보호, 사용자 역할/상태 변경 시 세션 폐기.
- [ ] DB CRUD·configVersion 경쟁 처리·soft delete·20개 상한, 내부 TargetProvider와 C lifecycle 연결.
- [ ] AES-256-GCM 저장·복호화 경계·키 버전/nonce·기존 평문 안전 이전.
- [ ] 공통 예외 응답·입력 검증·X-Request-Id·요청 크기 제한·보안 로그/감사 트랜잭션.
- [ ] IP 신뢰 proxy 규칙, outbound DB CIDR/포트 검사, 감사/접속 이력 API·V2 migration.

### C — 정책·위험도·사건·실시간·알림

- [ ] RiskAssessmentEngine을 C 소유로 재사용하고 cg:risk 소비에 연결, 스냅샷/중복 기록/사건/outbox 원자 처리.
- [ ] 기본 정책 두 규칙+시스템 두 규칙, 지속/복구·partial/null·오래된 이벤트 처리·OPEN unique 제약.
- [ ] LifecyclePort·stateVersion·삭제 tombstone·관리 종료 사유, 설정/정책 변경을 자동 복구와 구분.
- [ ] 상태/정책/사건 조회·필터, incidents/monitoring_states/risk_rule_states 및 V4 migration.
- [ ] Redis 그룹·pending reclaim·ACK·DLQ·24시간 안전 trim, 재시작/의존성 장애 복구.
- [ ] simple broker/STOMP CONNECT·SUBSCRIBE 인증·토큰 만료·세션 종료·payload 변환.
- [ ] Push/Slack CRUD, URL/endpoint 제한, 발송 작업 중복 방지·cooldown 병합·재시도·수신처 해제, Delivery API.

### 프론트 — 공통 로그인·관리·대시보드

- [ ] 공통 HTTP 래퍼, Access 메모리 보관, CSRF 부트스트랩, Web Locks/BroadcastChannel 갱신·로그아웃 동기화.
- [ ] 상태코드/code 분기, 204 빈 관측·404 대상 없음·409 버전 충돌·429 제한 처리. 임의 쓰기 자동 재전송 금지.
- [ ] name/실제 databaseName 구분, 계정·비밀번호 수정은 새 입력만 전송, USER 변경 버튼 숨김과 서버 거절 처리.
- [ ] null을 0으로 채우지 않기, 누적 Slow Query와 초당 증가율 라벨 구분, PAUSED/STALE/NO_DATA 표시.
- [ ] STOMP 재구독·버퍼/순서·중복 처리, 즉시/2초/30초 REST 대조, 이력으로 누락 구간 보충.
- [ ] 알림 권한 요청·서비스 워커·개인 구독 등록/해제, 동일 origin 사건 링크 이동. 로그인 후 이전 사건 경로 복귀.

공통 패키지 기준은 `com.example.monitoring.common`, 기존 A 코드는 collector/metric, B는 auth/database/audit, C는 risk/incident/realtime/notification으로 소유한다. 패키지 이동은 담당 변경을 드러내는 범위에서만 하고 계약 적용과 무관한 대규모 리팩터링은 묶지 않는다.

## 4. 요구사항 추적

| 기존 요구사항 | 규격 위치 | 완료 증거 |
| --- | --- | --- |
| AUTH-01~04, ROLE-01~02 | API 2절, 보안 1~5절 | 가입·로그인·회전·폐기·변조/만료/권한 거절 응답 |
| DB-01~03 | API 3절, 보안 6~7절, 운영 2절 | 암호화 등록 후 A 수집, 버전 충돌, Ping 결과 |
| MET-01~03 | 이벤트 1~2절, 운영 2절 | 0/null/warmup/실패 샘플, 실제 metricId 일치 |
| HIST-01~02 | API 4절, 운영 5절 | 조회 경계·정렬·건수·보관 삭제 검수 |
| LIVE-01~03 | 이벤트 5절, API 5절 | 인증 구독·상태 이벤트·재연결 후 최신값 복구 |
| RISK-01~03 | API 5절, 이벤트 3절 | 동일 입력/정책의 동일 결과, 접속 실패/미수집 구분 |
| INC-01~03 | 이벤트 3절, API 5절 | OPEN 1건·단계 변경·복구·재발·필터 결과 |
| NOTI-01~03 | API 7절, 이벤트 6절 | 실제 기기/Slack 수신·중복 억제·해제·실패 이력 |
| AUD-01~02 | API 6절, 보안 7절 | 행위자·대상·결과·신뢰 IP, ADMIN 전용 조회 |
| INT-01 | API 전체, 각 파트 OpenAPI | /v3/api-docs export와 실제 요청/응답의 필드 일치 |
| INT-02~03 | 이벤트 2~5절, 운영 5절 | 실제 프레임/그룹·ACK·재시작·재처리 결과 |

## 5. 필수 통합 시나리오

각 항목에 실행 날짜·기준 SHA·입력·관측 결과·증거 경로를 붙인다. 아래는 기대 결과이지 이번 턴의 실행 결과가 아니다.

| ID | 실행 | 기대 결과 |
| --- | --- | --- |
| T01 | 일반 가입에 role=ADMIN 주입 | 400, 정상 가입은 USER, 비밀번호 hash만 저장 |
| T02 | 로그인 후 일반 조회·관리 변경 | USER 조회 200, 변경 403; ADMIN 변경 성공 |
| T03 | 만료/변조 JWT, 로그아웃된 sid 재사용 | 401의 정확한 code, STOMP 종료 |
| T04 | Refresh 회전 후 이전 토큰 재사용, 동시에 여러 탭 갱신 | 재사용 sid 폐기; 정상 탭 갱신은 잠금으로 직렬화 |
| T05 | CSRF/Origin 누락·변조, Redis 인증 저장소 중단 | 각각 403/503, 보호 생략 없음 |
| T06 | DB 등록 후 PostgreSQL 원본 값/로그 확인 | 암호문만 저장, API/이벤트/로그에 비밀 없음, A는 접속 성공 |
| T07 | 다른 configVersion으로 PATCH, 수집 중 변경·삭제 | 409 또는 이전 수집 결과 폐기, 최신 상태 덮어쓰기 없음 |
| T08 | 비활성 대상 Ping·활성화 | Ping 가능·정기 상태 불변, 활성화 다음 주기에 수집 |
| T09 | 최신 스냅샷 없음·대상 없음·빈 기간 | 각각 204/404/200 [], 잘못된 기간 400 |
| T10 | t=0,5,10초 스냅샷에 history [0,10) | 0·5초만 반환, timestamp/id 정렬 일치 |
| T11 | 첫 수집·정상 0·카운터 초기화·접속/쿼리 오류 | warmup/reset null, 실제 0 유지, 올바른 status/errorCode |
| T12 | 같은 eventId 두 번 + ACK 직전 종료 | OPEN·발송 작업 1건, 재시작 후 pending ACK |
| T13 | PostgreSQL 성공 뒤 Redis 실패·publisher 재시작 | outbox 보존, 같은 ID로 발행 복구, DB와 event metricId 일치 |
| T14 | 비율 0.8 이상 t=0/5/10/15, 이후 0.7 t=20/25/30/35 | t=15 WARNING OPEN, t=35 RECOVERED; 경계값 >= 적용 |
| T15 | WARNING→CRITICAL→WARNING 반복, FATAL 상승 | 동일 incidentId, 단계별 지속/복구 조건과 incidentVersion 증가, 알림 cooldown 병합/FATAL 즉시 |
| T16 | 실패 이벤트 지속 vs 수집 프로세스 종료 vs Redis 중단 | CONNECTION_FAILURE와 COLLECTION_STALE 구분, Redis 장애를 수집기 사망으로 단정하지 않음 |
| T17 | 수동 중단·삭제·정책 변경 중 OPEN | 해당 resolutionReason으로 종료, 정상 복구 알림 없음 |
| T18 | 오래된 backlog·역순 이벤트·현재보다 낮은 configVersion | 현재 상태/알림을 과거로 되돌리지 않음 |
| T19 | 잘못된 schemaVersion/JSON·업무 불변식 오류 | 정해진 DLQ 정책, DLQ 저장 실패 시 ACK 안 함 |
| T20 | cg:risk와 cg:realtime으로 같은 메트릭 발행 | 각 그룹이 같은 eventId를 각각 처리 |
| T21 | JWT 없는 CONNECT·임의 wildcard SUBSCRIBE·topic SEND | 인증/인가 거절, 허용 topic만 수신 |
| T22 | REST 응답 지연 중 새 소켓 이벤트, 재연결/구독 등록 지연 | 버전 비교로 최신 유지, 2초/30초 대조로 누락 복구 |
| T23 | 브라우저 알림 허용/거부·모바일 설치 상태·서비스 워커 클릭 | 지원 환경 실제 수신, 거부는 미구독 표시, 같은 origin 사건으로 이동 |
| T24 | Slack 200 ok/429/500/410·수신처 삭제 | 성공/재시도/비활성·CANCELLED, 비밀 미노출 |
| T25 | 장애 발생 뒤 정상 복구, 발송 직전 상태 변경 | 수신한 개시 사건만 복구 알림, 오래된 OPEN 발송 취소 |
| T26 | 보관 경계 직전/직후 자료, 오래된 OPEN 사건 | 만료된 메트릭만 삭제, OPEN 사건·근거 값 보존 |
| T27 | 임의 전달 IP 헤더·허용 밖 DB/Push/Webhook 주소 | trusted proxy만 인정, 서버의 허용 밖 연결 차단 |
| T28 | baseline 기존 DB의 평문·시간·BLOCKED 데이터 이전 | 백업·행수/복호화 검증, 시간 의미 보존, 자동 활성화 없음 |

## 6. PR·배포 인수 기준

1. 기능 브랜치는 최신 develop에서 분기한다. 문서 작업은 `feature/docs-integration-contracts`, A/B/C 기능은 기존 feature/be-collector·be-auth·be-notification 범위를 사용한다. 통합 전 다른 담당의 공유 DTO를 임의로 바꾸지 않는다.
2. 각 API 담당자는 OpenAPI에 모든 DTO 필수/nullable/enum/단위·성공/오류 상태·Bearer/CSRF·권한을 작성한다. 생성 명세와 이 문서가 다르면 구현 또는 문서를 같은 PR에서 명시적으로 수정한다.
3. 변경한 계약의 실제 성공·빈 결과·실패 JSON과 Redis/STOMP 메시지를 보관한다. 명세 문장만으로 연동 성공을 판정하지 않는다.
4. 단위·계약 테스트 후 T01~T28 중 담당 범위와 전체 로그인→등록→수집→구독→장애→알림→복구 시나리오를 실제 환경에서 실행한다.
5. 미완료 기능은 완료로 표시하지 않는다. 코드 합치기/배포·외부 알림 실제 전송은 해당 실행 작업에서 수행한다. 이번 초안 작업은 저장소 업로드나 팀 메시지 전송을 포함하지 않는다.
