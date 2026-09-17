# 협업 가이드

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

## 공통 개발 규칙 및 사전 합의

| 항목 | 내용 |
| --- | --- |
| API 문서화 | REST API는 Swagger/OpenAPI로 작성하고 WebSocket 이벤트 메시지 규격도 문서화 |
| Git 협업 | 파트별 feature 브랜치, 기능 단위 커밋, PR 제출 후 코드 리뷰 |
| 이벤트 규격 | `MetricCollectedEvent`, `IncidentCreatedEvent`의 DTO 필드 형식 공동 정의 |
| Redis 전달 방식 | Streams/PubSub 선택과 C 재시작 시 이벤트 복구 범위 사전 합의. Streams를 사용할 경우 Consumer Group 구성도 정의 |
| 통합 테스트 | 로그인 → DB 등록 → 수집·대시보드 전송 → 임계치 초과·장애 알림 → 정상화·복구 검증 |

백엔드 언어·프레임워크와 Redis 전달 방식은 이 문서에서 최종 확정하지 않습니다.
