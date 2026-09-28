# Backend

백엔드 코드 디렉터리입니다.

- feature/be-auth: 인증·권한 및 모니터링 대상 DB 관리
- feature/be-collector: DB 수집·메트릭 저장 및 조회
- feature/be-notification: 실시간 전송·위험도 판단·장애 알림

빌드 기준은 Java 17 / Spring Boot 3.2.3 / Gradle Wrapper 8.5입니다. 구현할 공통 환경·실행 순서는 [저장·운영 규격](../docs/integration-operations.md), 파트 간 통신은 [팀 배포용 규격 v0.2](../docs/integration-contract-draft.md)를 따릅니다. 실제 통합 실행 완료를 의미하지 않습니다.
