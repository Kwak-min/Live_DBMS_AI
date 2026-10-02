# 저장·내부 인터페이스·운영 규격 초안 v0.2

[전체 기준](integration-contract-draft.md) / [API](api.md) / [보안](integration-security.md) / [이벤트](events.md). 아래는 active V4 통합과 이후 운영 경계를 정의하는 계약이며 shared deployment 완료를 뜻하지 않는다.

## 1. 공통 환경

| 구성 | 통합 기준 | 로컬 접근 |
| --- | --- | --- |
| Java / Gradle | JDK 17 / 저장소 Wrapper 8.5 | JAVA_HOME은 JDK 17 |
| Spring / springdoc | 기존 빌드의 3.2.3 / 2.3.0을 계약 구현 출발점으로 유지 | 8080, 보안 패치 업그레이드는 호환 검수 후 별도 반영 |
| 시스템 DB | PostgreSQL 16.x, UTF-8, UTC | localhost:5432 / monitoring_db |
| Redis | Redis 7.4.x, 환경별 별도 인스턴스 | localhost:6379 |
| 테스트 대상 | MariaDB 10.11.x, 실제 지원 검수 대상도 10.11.x | localhost:13306 (컨테이너 내부 3306) |
| 프론트 | 프레임워크에 관계없이 개발 origin http://localhost:5173 | /api 및 /ws를 8080으로 proxy |
| 운영 입구 | 단일 origin HTTPS 443, reverse proxy | 프론트 static, /api·/ws는 backend, Upgrade 지원 |

x는 해당 계열의 배포 시점 패치 버전이며 릴리스 산출물에는 실제 이미지 digest와 버전을 기록한다. 위 버전은 통합 호환 목표이지 최신/보안 검증 완료 선언이 아니다. PostgreSQL/Redis/MariaDB는 외부 인터넷에 포트를 공개하지 않는다. MVP 백엔드는 단일 replica로 배포하고 자동 수평 확장은 끈다.

프론트가 HTTP local 프로필을 쓸 때만 Secure=false 개발 쿠키를 허용한다. 모바일 Push 검수는 HTTPS staging 또는 기기에서 접근 가능한 신뢰 인증서 HTTPS origin에서 진행한다. 개발자의 localhost 주소를 다른 모바일 기기의 주소로 사용하지 않는다.

### 환경 변수 계약

| 변수 | 값/기본값 | 담당 |
| --- | --- | --- |
| SPRING_PROFILES_ACTIVE | local / staging / prod | A |
| PUBLIC_ORIGIN | local=http://localhost:5173, 운영은 실제 HTTPS origin, 경로·끝 slash 없음 | A |
| SPRING_DATASOURCE_URL | jdbc:postgresql://localhost:5432/monitoring_db (local) | A |
| SPRING_DATASOURCE_USERNAME / PASSWORD | 별도 실행 계정·비밀, 운영 기본 암호 없음 | A |
| SPRING_REDIS_HOST / PORT | localhost / 6379 (기존 키 유지) | A |
| SPRING_REDIS_PASSWORD | 운영 필수, local 격리 환경만 비워 둠 | A |
| JWT_SIGNING_KEYS / JWT_ACTIVE_KID | kid→base64 key JSON / 활성 kid | B |
| DB_CONFIG_ENCRYPTION_KEYS / DB_CONFIG_ACTIVE_KEY_VERSION | version→base64 32byte key JSON / 정수 활성 버전 | B |
| BOOTSTRAP_ADMIN_EMAIL / PASSWORD / DISPLAY_NAME | bootstrap 실행에만 주입; 종료 후 제거 | B |
| TARGET_DB_ALLOWED_CIDRS | 운영 필수. local 테스트는 127.0.0.1/32,::1/128와 지정한 테스트 네트워크 | A·B |
| TARGET_DB_ALLOWED_PORTS | prod=3306, local=3306,13306 | A·B |
| TARGET_DB_TLS_REQUIRED | prod/staging=true, local=false | A |
| TRUSTED_PROXY_CIDRS | 비어 있으면 전달 IP 헤더 전부 무시; 실제 proxy망 명시 | B |
| WEB_PUSH_VAPID_PUBLIC_KEY / PRIVATE_KEY / SUBJECT | base64url P-256 공개/개인키, 운영 연락 mailto 주소 | C |
| PUSH_ALLOWED_HOSTS | 보안 문서의 기본 host 집합 | C |
| REALTIME_ENABLED | 기본 false; A `processed_events`가 준비된 환경에서만 true | C |
| APP_COLLECTOR_ENABLED / APP_COLLECTOR_FIXED_RATE_MS | true / 5000 | A |
| APP_METRICS_RETENTION_DAYS | 30, 1~365 | A |
| LEGACY_TIME_ZONE | 이전 LocalDateTime 자료 마이그레이션 시 원래 JVM timezone, 예 Asia/Seoul | A |

환경 변수는 실행 프로세스/IDE의 환경 또는 배포 secret으로 주입한다. `.env` 자동 로더는 도입하지 않는다. `.env.example`은 키 목록/형식만 제공한다. 기존 JWT_SECRET·JWT_*_EXPIRATION_MS·DB_CONFIG_ENCRYPTION_KEY와 Webhook URL 환경변수는 v1 계약에서 사용하지 않는다. 토큰 시간은 코드 상수로 통일하고 Webhook은 관리 API로 등록한다. 실제 secret 값은 문서/예제/커밋에 넣지 않는다.

## 2. 파트 간 내부 서비스 계약

아래는 같은 JVM 안에서 호출하는 서비스 포트다. 공개 HTTP/Redis에 접속 비밀을 싣지 않는다. DTO를 JPA entity와 분리하며 각 파트의 repository를 직접 호출하지 않는다.

| 제공자 | 포트·입출력 | 호출자·실패 처리 |
| --- | --- | --- |
| B | TargetProvider.listEnabled(): TargetSummary[]; getForCollection(id): CollectorTarget | A. 삭제/비활성은 수집 생략, 복호화 오류는 실패 스냅샷 |
| B | TargetProvider.getMetadata(id,includeDeleted): TargetMetadata | A·C. 조회 전용, 비밀 없음 |
| B | AuthService.authenticate(token): AuthPrincipal; validateSession(sid): AuthPrincipal | A·C. 공통 인증 오류로 변환 |
| B | AuditRecorder.record(AuditInput): void | A·C. 변경과 같은 트랜잭션, 실패 시 롤백 |
| C | MonitoringLifecyclePort.applyChange(TargetChange): void | B. 필수 의존성. DB 등록/수정/삭제 트랜잭션 안에서 동기 호출하며 C 상태/정책/사건 갱신까지 원자적 |
| A 공통 기반 | OutboxWriter.append(eventId,type,payload): void | A·B·C. 호출자의 PostgreSQL 트랜잭션에 참여 |
| A | MetricQueryService.latest(id,configVersion): Metric 또는 null | C. 리플레이 복구/최신 상태 검증 |

TargetSummary는 id/configVersion/enabled. CollectorTarget은 databaseConfigId,configVersion,name,host,port,databaseName(nullable),username,password,enabled. TargetMetadata는 같은 필드에서 username/password를 제외하고 deletedAt,createdAt,updatedAt 포함. AuthPrincipal은 userId,role,sessionId,expiresAt.

TargetChange는 databaseConfigId,configVersion,changeType(CREATED/UPDATED/PAUSED/RESUMED/DELETED),enabled,name,occurredAt,actorId,requestId. 비밀값/호스트는 싣지 않는다. AuditInput은 API Audit에서 id를 제외한 필드다. 내부 호출의 clientIp는 B가 해석한 request context에서 받는다. 예약 작업은 actorId=null, clientIp="SYSTEM", 새 requestId를 쓴다.

B의 쓰기 서비스는 C의 LifecyclePort를 호출하되 C가 참조하는 B TargetProvider는 별도 조회 어댑터로 둔다. 같은 쓰기 서비스끼리 상호 생성자 의존을 만들지 않는다. 외부 Redis/HTTP 발송은 DB 트랜잭션 안에서 하지 않는다.

### 설정 변경 경쟁 처리

- B는 DatabaseConfig row lock 아래 configVersion 비교·변경, C lifecycle 갱신, 감사·outbox 기록을 한 트랜잭션으로 커밋한다.
- C lifecycle은 생성 시 기본 정책과 stateVersion=1 상태를 만들고, 변경/중단/삭제 시 stateVersion을 증가시킨다. 변경된 configVersion의 lastAttemptAt/lastSuccessAt/latestMetricId를 null로 초기화하고 해당 사유로 OPEN 사건을 종료한다.
- A는 수집 시작 시 configVersion을 캡처한다. 결과 저장 시 동일 대상 row를 잠그고 현재 configVersion·enabled·deletedAt을 다시 확인한다. 다르면 해당 완료 결과를 폐기하고 외부 이벤트도 발행하지 않는다. 동일하면 메트릭+outbox만 저장한다.
- C는 MetricCollectedEvent 처리 시 다시 현재 설정 버전을 확인한다. 구 버전 이벤트는 처리 기록 후 ACK하되 최신 상태를 바꾸지 않는다. 최종 상태·사건·outbox는 자신의 트랜잭션으로 저장한다.
- A가 B의 설정 전체 엔티티를 save하지 않는다. B의 기존 status reads와 A의 `database_configs` 네 칼럼 표시 writer는 metric-driven C state consumer가 live attempt/success/latest metric을 유지할 때까지 그대로 둔다. C lifecycle rows의 관리자 변경 상태만 이 통합에서 기록한다.

## 3. 저장 모델·소유권

공통: PK는 bigserial(외부 안전 정수 상한 CHECK), 시간은 timestamptz, 이벤트/사건/세션 ID는 UUID, enum은 varchar+CHECK, JSON payload는 jsonb. soft delete된 대상 ID는 재사용하지 않는다.

| 테이블 | 소유 | 주요 데이터·제약 |
| --- | --- | --- |
| users | B | id,email unique,display_name,password_hash,role,enabled,auth_version,created_at,updated_at |
| auth_sessions | B | sid UUID PK,user_id FK,refresh_hash unique,created_at,expires_at,revoked_at,auth_version |
| used_refresh_tokens | B | refresh_hash PK,sid FK,used_at,expires_at; 세션 종료까지 보관 |
| database_configs | B | id,name,host,port,database_name,username/password 암호문 구조,enabled,config_version,deleted_at,timestamps |
| metric_data | A | id,target FK,config_version,시각·지표·실패·nullable reason; index(target,timestamp DESC,id DESC) |
| monitoring_states | C | target PK/FK,config_version,state_version,연결/신선도/위험도,최신 metricId·시각; target당 1행 |
| risk_policies | C | target PK/FK,version,rules jsonb,stale_after_seconds,cooldown,timestamps |
| incidents | C | UUID PK,target FK,개시 당시 표시명,rule_id,type,severity,status,시각,reason,근거 값,incident_version |
| risk_rule_states | C | (target,rule_id) PK,현재 후보 단계·시작 시각·복구 시작 시각·마지막 관측·metricId |
| event_outbox | 공통 A 기반 | event_id UUID PK,event_type,payload,created_at,published_at,attempts,next_attempt_at |
| processed_events | 공통/각 소비자 | (stream,consumer_group,event_id) PK,processed_at |
| audit_logs / access_logs | B | API의 이력 필드, index(occurred_at DESC,id DESC); 감사와 접속 조회 분리 |
| push_subscriptions | C | id,user_id,sid,endpoint_hash,암호화 payload,expiration_time,enabled,deleted_at,timestamps; 활성 endpoint_hash만 partial unique |
| notification_webhooks | C | id,name,provider=SLACK,암호화 URL,enabled,deleted_at,timestamps |
| notification_deliveries | C | id,incident_id,incident_version,channel,recipient_id,status,attempt_count,eligible_at,expires_at,next_attempt_at,error,sent_at |
| blocked_reasons (legacy) | A 보존 | 기존 차단 이력 읽기 전용, v1 신규 쓰기 없음, 180일 보관 |

incidents는 `(database_config_id,rule_id) WHERE status='OPEN'` partial unique index로 중복 사건을 막는다. notification_deliveries는 `(incident_id,incident_version,channel,recipient_id)` unique이며 `expires_at = eligible_at + interval '600 seconds'` 제약을 둔다. 처음 cooldown 대기열에 들어간 비-FATAL 상승 작업의 `eligible_at`·`expires_at`은 최초 값으로 고정하고, 더 최신 비-FATAL 상승은 같은 작업 내용만 갱신한다. FATAL 상승은 대기 중인 비-FATAL 작업을 취소/대체하고 즉시 작업을 만든다. `now < eligible_at`은 cooldown 대기, `eligible_at <= now < expires_at`은 외부 발송·재시도 창, `now >= expires_at`은 CANCELLED다. 즉시 작업은 생성 시각을 `eligible_at`으로 삼고 동일한 600초 창을 사용한다. 두 시각과 `next_attempt_at`은 재시작 뒤에도 저장값을 사용한다. resolved_at과 resolution_reason은 OPEN이면 둘 다 null, RESOLVED이면 둘 다 존재해야 한다.

메트릭 보관 삭제가 사건을 지우지 않도록 incidents.source_metric_id는 nullable FK ON DELETE SET NULL이다. 사건의 metricName/value/threshold는 스냅샷으로 보존한다. 대상은 물리 삭제하지 않으므로 과거 사건/이력의 FK가 끊어지지 않는다. Push/Webhook 삭제는 disabled+deleted_at tombstone으로 남겨 재시도·결과 조회의 수신처 ID를 보존한다.

## 4. 마이그레이션

- Flyway를 공통 도구로 사용하고 Hibernate ddl-auto=validate로 고정한다. A가 마이그레이션 순서·버전 등록을 관리한다.
- V1은 기준 커밋의 기존 스키마 생성, V2는 B의 계정/보안/설정 변환, V3는 A의 지표/outbox/중복 처리, V4는 C의 상태/정책/사건/수신처 구조다. V4는 forward-only이며 V1/V2/V3를 수정하지 않는다. 파트는 지정 파일을 소유하고 후속 버전은 통합 브랜치의 마지막 번호+1로 등록한다.
- Active V4는 `database_configs`를 `SHARE ROW EXCLUSIVE`로 잠근 뒤 두 named composite key를 만들고 C foreign key를 생성한다. unsafe ID/config version 또는 enabled soft-deleted retained row를 처음 발견하면 target 식별 진단과 함께 migration 전체를 rollback한다. 정상 backfill은 모든 target에 실제 `config_version`을 가진 state 1개와 default policy version 1개를 만들고, enabled/nondeleted에 하나의 millisecond transaction epoch를 공유한다. 역사 metric으로 C 필드를 채우거나 migration lifecycle outbox event를 만들지 않는다.
- 기존 스키마가 있는 DB는 백업하고 V1 스키마와 일치하는지 검사한 뒤 명시적 Flyway baseline 1을 적용한다. baselineOnMigrate는 false. 다른 스키마는 자동으로 승인/삭제하지 않고 차이를 기록하여 별도 migration으로 보존 변환한다.
- V2는 기존 평문 username/password를 활성 키로 암호화한 뒤 복호화 왕복 검사를 통과한 행만 전환한다. 전체 성공 후에만 평문 컬럼을 제거한다. 키가 없거나 실패하면 migration 실패·롤백, 부분 완료 서버 기동 금지.
- 기존 LocalDateTime은 `LEGACY_TIME_ZONE`을 명시해 timestamptz로 변환한다. 원래 timezone을 모르면 시간을 추측해 변환하지 않고 배포를 실패시킨다. 이는 실제 데이터의 입력 정보이며 선택할 설계 항목이 아니다.
- 기존 BLOCKED는 enabled=false로 이전하고 과거 사유를 보존한다. 관리자 PATCH로 명시적으로 다시 활성화할 때까지 수집하지 않는다. 자동 재활성화하지 않는다.
- 운영 롤백은 코드와 데이터 스키마 호환성을 먼저 확인한다. down migration으로 사용자/이력을 삭제하지 않는다. 백업 복구가 필요하면 복구 시점 이후 데이터 손실 범위를 기록한다.

## 5. 보관·백업·장애 복구

| 데이터 | 보관·정리 규칙 |
| --- | --- |
| metric_data | 30일; 매일 03:00 UTC, 작은 배치로 삭제, 사건 근거 스냅샷 보존 |
| incidents | RESOLVED 종료 후 180일, OPEN은 나이와 무관하게 유지 |
| audit_logs / blocked_reasons | 발생 후 180일 |
| access_logs / notification_deliveries | 발생 후 30일 |
| 완료 outbox / processed_events | 31일; 미발행 outbox는 기간 삭제 금지 |
| used_refresh_tokens / auth_sessions | 세션 만료/폐기 후 1일(재사용 판정에 필요한 상태 포함) |
| Redis Stream | 최소 24시간, 미처리/pending을 넘겨 자르지 않음; DLQ는 7일과 PostgreSQL/export 기록 |
| 데이터 백업 | PostgreSQL 매일 02:00 UTC 암호화 백업, 7일 보관; 초기 목표 RPO 24h/RTO 4h |

Redis AOF everysec, maxmemory-policy=noeviction을 사용한다. 메모리 80% 경보, 90%에서 수동 점검하고 오래된 안전 처리 구간만 정리한다. 키 전체를 자동 삭제하지 않는다. Redis 복구 시 A/C outbox를 같은 eventId로 재발행하고 누락 상태를 PostgreSQL에서 복구한다.

outbox publisher는 1초 주기, 최대 100건, 대상별 생성 순서대로 발행한다. 재시도는 1/2/4/8/16/30초 상한의 지수 backoff, 의존성 장애에서는 횟수로 포기하지 않는다. 같은 eventId 재발행은 정상 상황이다. 멀티 publisher는 MVP에서 실행하지 않는다.

모든 수집 작업은 전체 15초 제한 내에 JDBC statement 취소·connection close로 종료한다. 실행 중인 대상의 다음 tick은 건너뛰며 동시에 두 수집을 하지 않는다. 실패 스냅샷도 가능한 한 PostgreSQL에 저장한다. PostgreSQL이 안 되면 미저장 관측을 Redis에만 먼저 발행하지 않고 운영 오류와 생존 신호를 남긴다.

프로세스 시작 순서: 기존 application writer 중지·drain 및 새 binary 준비 전까지 write fence → PostgreSQL/Redis 준비 → V4 Flyway 적용(`database_configs` lock은 migration commit까지 유지) → 필요하면 최초 Admin bootstrap → 새 backend binary → frontend/proxy → writer 재개. DB lock은 migration commit에서 끝나며, old-writer fence는 새 binary가 준비될 때까지 유지한다. 재시작 시 C는 저장된 상태/OPEN 사건을 읽고 지속 시간 후보를 초기화하며, 향후 notification worker가 사용할 `eligible_at`·`expires_at`·`next_attempt_at`을 복원해 cooldown 대기와 active send/retry 창을 구분한다. delayed worker의 만료/재시작 규칙은 future notification worker가 구현할 운영 계약이다. 기존 사건을 INFO로 강제 복구하지 않는다. Redis 지연/누락 중 실시간 전송은 제한되지만 저장된 REST 이력은 조회 가능하다. 이 문서는 handoff만 기록하며 shared deployment 완료를 주장하지 않는다.

최초 v1 전환은 기존 consumer/수집기를 중지하고 PostgreSQL·Redis를 백업한다. 기존 무버전 Stream을 `archive:v0:<UTC기준시각>:<원래키>`로 rename하여 7일 보존하고 같은 기존 이름으로 v1 Stream을 새로 만든다. 보관 복사와 건수 확인 전에는 삭제하지 않는다. 구버전 이벤트를 v1 consumer에 투입하지 않는다. 시간·지표 의미가 달라 자동 무손실 변환을 가정하지 않는다.

## 6. 관측·실행 검수

- JSON 운영 로그: timestamp,level,service,requestId,eventId(optional),databaseConfigId(optional),operation,errorCode,durationMs. 민감 데이터는 모든 level에서 제외한다.
- 경보: outbox oldest >30초, consumer lag >30초, pending oldest >60초, DLQ >0, collector cycle >15초, DB pool 고갈, 알림 실패율. 이 값은 운영 관측이며 대상 DB 위험도와 섞지 않는다.
- readiness는 PostgreSQL 필수 연결/Flyway 정상 여부로 판단한다. Redis 장애는 상태를 DEGRADED로 기록하고 REST 읽기를 유지하며 로그인/CSRF 의존 기능은 503. liveness는 외부 DB/Redis 장애만으로 실패시키지 않는다.
- 배포 패키지에는 실제 JDK/이미지 버전·digest, migration 버전, secret 변수 이름 목록, OpenAPI export, 샘플 Redis/STOMP 프레임, 통합 검수 결과를 포함한다.
- 소스 변경 없는 이번 문서 작업에서는 DB/Redis 실행·배포·기능 테스트를 수행한 것으로 처리하지 않는다.
