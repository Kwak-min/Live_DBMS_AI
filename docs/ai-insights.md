# AI 인사이트 (일일 보고서·위험 쿼리 분석)

초기 기획에서 Phase 2로 미뤘던 AI 기능 두 가지를 백엔드에 구현했다. Claude API(Anthropic)를 쓰며 기본은 꺼져 있다(`AI_ENABLED=false`). 꺼져 있어도 조회 API와 화면은 동작하고, 생성 요청만 503 `AI_UNAVAILABLE`이다.

| 기능 | 하는 일 | 생성 방식 |
| --- | --- | --- |
| 일일 DB 상태 보고서 | 하루치 `metric_data`·`incidents`를 집계해 요약·건강 점수·발견 사항·권고를 만든다. 근거 집계(시간대별 포함)도 함께 저장한다. | 매일 00:10(보고 시간대 기준)에 전날 보고서 자동 생성 + ADMIN 수동 요청 |
| 위험 쿼리 분석 | 대상 MariaDB에서 무거운 쿼리 상위 20개를 읽어 위험도·문제·개선안·인덱스 제안을 붙인다. | ADMIN 수동 요청 |

## 1. 프론트 연동

생성은 비동기다. POST는 바로 202와 `status=PENDING` 보고서를 돌려주고, 프론트는 `GET /api/v1/ai/reports/{reportId}`를 2~3초 간격으로 조회해 `SUCCEEDED`나 `FAILED`가 되면 멈춘다. 보통 10~60초 걸린다.

| Method / 경로 | 요청 | 응답·권한 |
| --- | --- | --- |
| GET `/api/v1/ai/status` | 없음 | 200 AiStatus, USER/ADMIN. `available=false`면 생성 버튼을 숨긴다. |
| POST `/api/v1/databases/{id}/ai/daily-report` | query `date`(YYYY-MM-DD, 선택. 기본 어제) | 202 AiReport(PENDING), ADMIN |
| POST `/api/v1/databases/{id}/ai/query-analysis` | 없음 | 202 AiReport(PENDING), ADMIN |
| GET `/api/v1/ai/reports` | databaseConfigId, type, status, date, page, size 모두 선택 | 200 AiReport 페이지, requestedAt DESC·id DESC, USER/ADMIN |
| GET `/api/v1/ai/reports/{reportId}` | 없음 | 200 AiReport, USER/ADMIN |

오류는 공통 `ApiErrorResponse`다.

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | id·date·필터 형식 오류. date는 오늘 이전이고 메트릭 보관 기간(30일) 이내여야 한다. |
| 404 | DATABASE_NOT_FOUND / AI_REPORT_NOT_FOUND | 대상 또는 보고서 없음 |
| 409 | AI_REPORT_IN_PROGRESS | 같은 대상(일일 보고서는 같은 날짜)으로 이미 생성 중 |
| 422 | AI_NO_DATA | 그날 수집된 메트릭이 없음 |
| 429 | RATE_LIMITED | 같은 대상·종류는 60초에 한 번. `Retry-After` 헤더 |
| 503 | AI_UNAVAILABLE / AI_BUSY / DEPENDENCY_UNAVAILABLE | AI 꺼짐·키 없음 / 대기열 가득 참 / Redis 장애 |

AiStatus: `{available:boolean,model:Text,timeZone:Text,dailyReportScheduled:boolean,requestCooldownSeconds:int}`.

AiReport: `{id:Id,type:DAILY_REPORT|QUERY_ANALYSIS,databaseConfigId:Id,databaseName:Text,reportDate:Date?,windowStart:Time?,windowEnd:Time?,status:PENDING|SUCCEEDED|FAILED,triggerSource:SCHEDULED|MANUAL,model:Text,requestedAt:Time,completedAt:Time?,errorCode:Text?,errorMessage:Text?,inputTokens:int?,outputTokens:int?,dailyReport:DailyReport?,queryAnalysis:QueryAnalysis?}`.

- `dailyReport`는 `SUCCEEDED`인 DAILY_REPORT에만, `queryAnalysis`는 `SUCCEEDED`인 QUERY_ANALYSIS에만 있다.
- `errorCode`는 `FAILED`일 때만 있다: `AI_REFUSED`, `AI_TRUNCATED`, `AI_RATE_LIMITED`, `AI_AUTH_FAILED`, `AI_UPSTREAM_ERROR`, `AI_INVALID_OUTPUT`, `AI_BUSY`, `TARGET_UNREACHABLE`, `CREDENTIALS_UNAVAILABLE`, `INTERRUPTED`(서버 재시작), `INTERNAL_ERROR`. `errorMessage`는 사용자에게 그대로 보여 줘도 되는 한국어 문장이다.
- `reportDate`는 보고 시간대(`AiStatus.timeZone`, 기본 Asia/Seoul)의 날짜이고 `windowStart`/`windowEnd`는 그 하루의 UTC 경계다.

DailyReport: `{reportDate:Date,timeZone:Text,summary:Text,overallStatus:HEALTHY|WARNING|CRITICAL,healthScore:int(0~100),findings:Finding[],recommendations:Text[],stats:DailyStats,previousDayStats:DailyStats?}`.

- Finding: `{severity:INFO|WARNING|CRITICAL,title:Text,detail:Text}`.
- DailyStats: 표본 수(`sampleCount`, `successCount`, `partialFailureCount`, `connectionFailedCount`), `availabilityPercent`, 연결(`avgActiveConnections`, `maxActiveConnections`, `maxConnections`, `avgConnectionUsagePercent`, `maxConnectionUsagePercent`), `avgQps`/`maxQps`, `slowQueriesTotal`, `maxSlowQueriesPerSecond`, `avgThreadsRunning`/`maxThreadsRunning`, `avgResponseTimeMs`/`p95ResponseTimeMs`, `storageBytesStart`/`storageBytesEnd`, `errorCounts`(errorCode별 건수), `incidentCount`, `incidents`(최대 50건), `hourly`(1시간 단위 `{hourStart,samples,failedSamples,avgConnectionUsagePercent,avgQps,slowQueries,avgResponseTimeMs}`). 수집되지 않은 값은 0이 아니라 null이다.
- `previousDayStats`는 전날 표본이 있을 때만 있고 `incidents`·`hourly`는 빈 배열이다.

QueryAnalysis: `{source:PERFORMANCE_SCHEMA|PROCESSLIST,collectedAt:Time,summary:Text,overallRisk:LOW|MEDIUM|HIGH|CRITICAL,queries:AnalyzedQuery[],generalRecommendations:Text[]}`.

- AnalyzedQuery: `{sample:QuerySample,riskLevel:LOW|MEDIUM|HIGH|CRITICAL|null,category:Text?,problem:Text?,recommendation:Text?,suggestedIndex:Text?}`. 위험도 높은 순이다. AI가 판정을 빠뜨린 표본은 `riskLevel` 이하가 null이다.
- QuerySample: `{queryId,schemaName?,queryText,executions?,totalLatencyMs?,avgLatencyMs?,maxLatencyMs?,rowsExamined?,rowsSent?,rowsAffected?,noIndexUsedCount?,noGoodIndexUsedCount?,tmpDiskTables?,sortMergePasses?,runningSeconds?}`. PERFORMANCE_SCHEMA 표본은 누적 통계를, PROCESSLIST 표본은 `runningSeconds`만 가진다.
- 표본이 0건이면 AI를 부르지 않고 `queries=[]`와 performance_schema 설정 안내를 담아 SUCCEEDED로 끝낸다.

## 2. 실행 설정

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `AI_ENABLED` | false | 생성 기능 켜기 |
| `ANTHROPIC_API_KEY` | (없음) | Claude API 키. 비밀값이며 저장소·문서에 넣지 않는다. |
| `AI_MODEL` | claude-opus-5-5 | 모델 ID |
| `AI_EFFORT` | medium | low / medium / high / xhigh / max |
| `AI_REFUSAL_FALLBACK` | true | 안전 분류기 거절 시 서버 측 대체 모델 재시도(`fallbacks: "default"`) |
| `AI_DAILY_REPORT_ZONE` | Asia/Seoul | 하루의 기준 시간대 |
| `AI_DAILY_REPORT_SCHEDULE_ENABLED` | true | 매일 00:10 전날 보고서 자동 생성 |
| `AI_REQUEST_COOLDOWN_SECONDS` | 60 | 수동 요청 간격(대상·종류별) |

- 생성은 백엔드 안의 전용 스레드 2개에서 돌고 대기열은 50건이다. 단일 인스턴스 배포를 전제로, 재시작 시 끝나지 못한 PENDING은 `INTERRUPTED`로 닫는다.
- 보고서는 180일 보관 후 매일 정리한다(`ai_reports`, V6 migration).
- 자동 생성은 활성 대상 중 그날 메트릭이 있고 아직 성공·진행 중 보고서가 없는 대상만 만든다.
- 비용은 보고서 1건당 입력 수천~1만 토큰 수준이다. 응답의 `inputTokens`/`outputTokens`로 확인한다.

## 3. 위험 쿼리 분석의 대상 DB 조건

- `performance_schema=ON`이면 `performance_schema.events_statements_summary_by_digest`에서 누적 시간 상위 20개를 읽는다. MariaDB는 기본이 OFF라서 `my.cnf`에 `performance_schema=ON`을 넣고 재시작해야 한다. 모니터링 계정에 `GRANT SELECT ON performance_schema.* TO 'monitor'@'%'`가 필요하다.
- 꺼져 있거나 읽을 수 없으면 `information_schema.PROCESSLIST`에서 지금 실행 중인 문장을 오래 걸린 순으로 읽는다. 다른 세션 문장을 보려면 `PROCESS` 권한이 필요하다.
- 읽기 전용 조회만 실행하고 대상 DB의 설정·통계를 바꾸지 않는다. 접속은 기존 대상 주소 정책(허용 CIDR·포트·TLS)을 그대로 따른다.

## 4. 외부로 보내는 데이터

Claude API로 보내는 것과 보내지 않는 것을 구분한다.

- 보낸다: 대상 표시 이름, 메트릭 집계, 사건 요약(규칙·수치·메시지), 리터럴을 지운 쿼리 문장과 통계.
- 보내지 않는다: 대상 host·port·계정·비밀번호, 사용자 정보, 원본 쿼리 값.
- 쿼리 문장은 PERFORMANCE_SCHEMA digest(이미 값이 `?`로 바뀐 문장)든 PROCESSLIST 원문이든 백엔드의 `SqlLiteralRedactor`가 문자열·숫자·16진수 리터럴과 주석을 `?`로 바꾼 뒤 보낸다. 테이블·컬럼 이름은 남는다.
- 실데이터 DB에 켜기 전에 이 범위를 팀·데이터 소유자와 확인한다.
