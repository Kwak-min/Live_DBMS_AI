# Live_DBMS_AI

실시간 DB 모니터링 시스템 프로젝트입니다.

## 폴더 구조

```text
Live_DBMS_AI/
├── frontend/  # 프론트엔드 코드
├── backend/   # 백엔드 코드
└── docs/      # 요구사항·API·이벤트 명세
```

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

- main: 안정 버전
- develop: 개발 통합
- feature/*: develop에서 분기하고 작업 후 develop으로 PR
- 배포할 변경은 develop에서 main으로 PR

위 구조는 분기·병합 규칙입니다. Git 브랜치 자체에는 부모·자식 관계가 저장되지 않습니다.
기존 루트의 HTML 자료는 원래 위치에 보존합니다.
