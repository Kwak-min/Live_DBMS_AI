# Backend

백엔드 코드 디렉터리입니다.

- feature/be-auth: 인증·권한 및 모니터링 대상 DB 관리
- feature/be-collector: DB 수집·메트릭 저장 및 조회
- feature/be-notification: 실시간 전송·위험도 판단·장애 알림

빌드 기준은 Java 17 / Spring Boot 3.2.3 / Gradle Wrapper 8.5입니다. 구현할 공통 환경·실행 순서는 [저장·운영 규격](../docs/integration-operations.md), 파트 간 통신은 [팀 배포용 규격 v0.2](../docs/integration-contract-draft.md)를 따릅니다. 실제 통합 실행 완료를 의미하지 않습니다.

## DB 마이그레이션 (Flyway)

시스템 DB 스키마는 Flyway로만 변경하며 Hibernate는 `ddl-auto: validate`로 검증만 합니다. 마이그레이션 순서·번호 등록은 A가 관리합니다. V1(A, 기준 스키마) → V2(B) → V3(A) → V4(C). 다른 파트의 migration 파일은 수정하지 않습니다.

- 새(빈) DB: 애플리케이션 기동 시 자동 적용됩니다.
- 예전 `ddl-auto: update`로 테이블이 이미 만들어진 로컬 DB: `baseline-on-migrate`가 꺼져 있어 기동이 실패합니다(의도된 동작). 테스트 데이터만 있다면 DB를 새로 만드는 것이 가장 간단합니다. 데이터를 보존해야 하면 백업 후 스키마가 V1과 같은지 확인하고 `flyway baseline -baselineVersion=1`을 명시적으로 실행합니다.
- `MigrationV1SchemaTest`는 내장 PostgreSQL 16에서 V1과 기존 엔티티의 Hibernate 생성 스키마(컬럼·제약·인덱스)가 같은지, V1 위에서 `validate`가 통과하는지 검증합니다.
