# Live_DBMS_AI

MariaDB의 상태와 성능 지표를 수집하고, 실시간 대시보드·위험도 판단·장애 알림을 제공하는 프로젝트입니다.

현재는 프로젝트 초기 구조와 백엔드 MVP 업무 범위를 정리한 단계입니다. 아래 내용은 구현 완료 내역이 아닌 개발 명세입니다.

## 폴더 구조

```text
Live_DBMS_AI/
├── frontend/       # 프론트엔드 코드
├── backend/        # 백엔드 코드
└── docs/           # 요구사항·API·이벤트 명세
```

- [요구사항 명세](docs/requirements.md)
- [API 명세](docs/api.md)
- [이벤트 명세](docs/events.md)

세부 명세 파일은 현재 작성용 틀입니다.

## 브랜치 구조

```text
main
└── develop
    ├── feature/fe-login
    ├── feature/fe-dashboard
    ├── feature/be-auth
    ├── feature/be-collector
    └── feature/be-notification
```

| 브랜치 | 용도 |
| --- | --- |
| main | 안정 버전 |
| develop | 개발 통합 |
| feature/fe-login | 프론트엔드 로그인 |
| feature/fe-dashboard | 프론트엔드 대시보드 |
| feature/be-auth | 백엔드 인증·권한·대상 DB 관리 |
| feature/be-collector | 백엔드 수집·메트릭 저장·조회 |
| feature/be-notification | 백엔드 실시간 전송·위험도 판단·장애 알림 |

기능 브랜치는 develop에서 분기하고 작업 후 develop으로 PR을 제출합니다. 배포할 변경은 develop에서 main으로 PR을 제출합니다. 위 트리는 협업 흐름을 나타내며 Git이 부모·자식 브랜치 관계를 저장한다는 뜻은 아닙니다.

## 백엔드 3인 업무 분배

### 파트 A — DB 수집 엔진 및 메트릭 데이터 관리

담당: 팀장 · 관련 브랜치: `feature/be-collector`

MariaDB 메트릭 수집, 원본 데이터 저장·조회 및 Redis 이벤트 발행을 담당합니다. 위험도는 판단하지 않습니다.

| 태스크 | 구현 범위 |
| --- | --- |
| MariaDB 원격 연결 관리 | 대상 DB 연결, Ping 확인, `SELECT VERSION()`을 통한 기본 메타데이터 조회 |
| 주기적 DB 상태 수집 | Connection 수, QPS, Slow Query 등 지표 수집. 실제 0과 수집 실패 Null/Error를 구분하여 기록 |
| 메트릭 규격화·이벤트 발행 | 수집 결과, 시도·성공 시각, 실패 원인을 `MetricCollectedEvent` DTO로 규격화하여 Redis에 발행 |
| 메트릭 저장·조회 API | 지표 보관·자동 삭제, 장애 전후 비교용 최신·기간별 조회 REST API. B의 Auth 미들웨어 적용 |

주요 산출물: DB Collector Engine, Metric DTO, Redis Event Publisher, Metric History REST API.

### 파트 B — 사용자 인증 및 모니터링 대상 DB 관리

담당: 팀원 1 · 관련 브랜치: `feature/be-auth`

사용자 계정, RBAC, DB 접속 정보 암호화 CRUD 및 공통 인증을 담당합니다.

| 태스크 | 구현 범위 |
| --- | --- |
| 회원가입·로그인 | 비밀번호 단방향 해시 저장, JWT Access/Refresh 토큰 발급·갱신 등 생명주기 관리 |
| RBAC 권한 제어 | Admin은 전체 CRUD·정책 변경, User는 모든 DB 조회·대시보드 구독. API·소켓 공통 인증 제공 |
| 모니터링 DB CRUD | DB 등록·조회·수정·삭제. ENV 기반 AES-256 대칭키로 접속 정보 암호화 저장, 비밀번호 원문 비노출 |
| 접속·감사 로그 API | 원격 접속 IP(`X-Forwarded-For`), 최근 갱신 시간, DB·권한 변경 등 사용자 행동 기록·조회 |

주요 산출물: Auth API, RBAC Middleware, Encrypted DB Management API, Audit Log API.

### 파트 C — 실시간 대시보드·위험도 판단·알림 파이프라인

담당: 팀원 2 · 관련 브랜치: `feature/be-notification`

실시간 소켓 전송, 임계치·수집 중단 판단 및 중복 억제 알림을 담당합니다.

| 태스크 | 구현 범위 |
| --- | --- |
| 실시간 WebSocket 서버 | Redis의 `MetricCollectedEvent`를 수신하여 인증된 대시보드 클라이언트에 전송 |
| 위험도·수집 중단 감지 | 지표 임계치·지속 시간 기반 4단계 위험도 산출, 타임아웃 기반 수집 프로그램 중단(Heartbeat) 감지 |
| 장애 사건·중복 억제 | `IncidentCreatedEvent` 생성, OPEN/RESOLVED 상태 관리, 동일 장애 알림 중복 억제·쿨타임 |
| 모바일 알림·장애 이력 API | 모바일 Web Push 및 메신저 Webhook 비동기 발송, 대상·기간·심각도별 장애 발생·복구 이력 조회 |

주요 산출물: WebSocket Server, Risk & Heartbeat Engine, Notification Dispatcher, Incident History API.

## 데이터 및 책임 흐름

```text
MariaDB 여러 대
    │
    ▼
A · DB 수집기 ──► 원본 메트릭 저장 ──► 최신·기간별 조회 API
    │ MetricCollectedEvent (성공 여부·실패 유형·관측 시각)
    ▼
Redis (전달 방식 사전 합의)
    ├──► C · WebSocket Server ──► 대시보드
    └──► C · Risk Engine ──► 장애 판단·수집 중단 감지
                               │ IncidentCreatedEvent
                               ▼
                          알림 억제 큐
                               │
                               ▼
                        Web Push / Webhook

B · Auth / RBAC ──► A의 REST API, B의 DB CRUD, C의 WebSocket 인증·권한 검사
```

## 공통 개발 규칙 및 사전 합의

| 항목 | 내용 |
| --- | --- |
| API 문서화 | REST API는 Swagger/OpenAPI로 작성하고 WebSocket 이벤트 메시지 규격도 문서화 |
| Git 협업 | 파트별 feature 브랜치, 기능 단위 커밋, PR 제출 후 코드 리뷰 |
| 이벤트 규격 | `MetricCollectedEvent`, `IncidentCreatedEvent`의 DTO 필드 형식 공동 정의 |
| Redis 전달 방식 | Streams/PubSub 선택과 C 재시작 시 이벤트 복구 범위 사전 합의. Streams를 사용할 경우 Consumer Group 구성도 정의 |
| 통합 테스트 | 로그인 → DB 등록 → 수집·대시보드 전송 → 임계치 초과·장애 알림 → 정상화·복구 검증 |

백엔드 언어·프레임워크와 Redis 전달 방식은 이 문서에서 최종 확정하지 않습니다.
