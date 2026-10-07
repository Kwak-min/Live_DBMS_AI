# Live_DBMS_AI

MariaDB의 상태와 성능 지표를 수집하고, 실시간 대시보드·위험도 판단·장애 알림을 제공하는 프로젝트입니다.

2026-10-06 기준 develop에 백엔드 A(수집·메트릭·outbox), B(인증·DB 관리·보안), C(상태·위험도·사건·실시간·알림) 코드가 반영되어 있습니다. C 기능은 `RISK_ENABLED`·`REALTIME_ENABLED`·`NOTIFICATIONS_ENABLED`가 기본 `false`입니다. AI 일일 보고서·위험 쿼리 분석(Gemini 기본, Claude 선택)도 구현되어 있으며 `AI_ENABLED`가 기본 `false`입니다([AI 인사이트](docs/ai-insights.md)). 프론트엔드 코드는 아직 이 저장소에 없습니다. 통합 시나리오 진행 상황은 [담당별 적용·검수표](docs/integration-handoff.md)를 확인하세요.

## 폴더 구조

```text
Live_DBMS_AI/
├── frontend/       # 프론트엔드 코드
├── backend/        # Java / Spring Boot 백엔드
└── docs/           # 요구사항·API·이벤트 명세 및 협업 문서
```

## 프로젝트 문서

- [팀 통신·연동 규격 초안 v0.2](docs/integration-contract-draft.md) — 현재 진행 현황, 개발 기준·기본값, 담당 경계
- [REST API 규격 초안](docs/api.md) — 현재 API와 합의 제안 구분
- [Redis·STOMP 이벤트 규격 초안](docs/events.md) — 지표·메시지·전달·복구 규칙
- [인증·보안 규격](docs/integration-security.md) · [저장·운영 규격](docs/integration-operations.md) · [담당별 적용·검수표](docs/integration-handoff.md)
- [백엔드 업무 분배 및 데이터 흐름](docs/backend-responsibilities.md)
- [브랜치·커밋·PR 규칙 및 사전 합의](docs/contributing.md)
- [백엔드 기능 요구사항](docs/backend-functional-requirements.md)
- [AI 인사이트](docs/ai-insights.md) — 일일 보고서·위험 쿼리 분석 API, 설정, 외부 전송 데이터 범위

## 실행 안내

**로컬에서 백엔드를 띄워 프론트를 연결하려면 [로컬 실행 가이드](docs/local-run-guide.md)를 따르세요.**

백엔드 빌드 설정은 Java 17, Spring Boot 3.2.3, Gradle Wrapper 8.5입니다. PostgreSQL·Redis 및 수집 대상 MariaDB가 필요합니다. 접속 환경 변수는 [backend/.env.example](backend/.env.example), 설정 기본값은 [application.yml](backend/src/main/resources/application.yml)을 확인하세요. `.env` 자동 로딩은 별도 설정이 필요하며, 팀 공통 실행 목표는 저장·운영 규격에 정리했으며 실제 환경 구성·통합 검증은 후속 구현 작업입니다.
