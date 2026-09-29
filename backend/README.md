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
- `MigrationSchemaTest`는 내장 PostgreSQL 16에서 전체 migration을 적용한 뒤 모든 엔티티가 `validate`를 통과하는지, V1 기존 데이터가 최신 버전까지 이전되는지 검증합니다. 새 migration이나 엔티티를 추가하면 이 테스트가 통과해야 합니다. (V1이 기준 엔티티의 Hibernate 생성 스키마와 컬럼·제약·인덱스까지 같다는 점은 PR #4에서 1회 검증했습니다.)

## 공통 outbox (A 제공, A·B·C 사용)

Redis로 나가는 내부 이벤트는 직접 발행하지 않고 `common.outbox.OutboxWriter`로 업무 데이터와 같은 트랜잭션에 기록합니다. `OutboxPublisher`가 1초마다 최대 100건을 Redis Stream(hash 필드 `payload` 하나)으로 발행하며, Redis 실패 시 1/2/4/8/16/30초 backoff로 무기한 재시도합니다.

```java
@Transactional
public void resolve(...) {
    incidentRepository.save(incident);
    outboxWriter.append(eventId, OutboxEventType.INCIDENT_RESOLVED, "database:" + databaseConfigId, payloadDto);
}
```

- 트랜잭션 밖에서 호출하면 예외가 납니다(`Propagation.MANDATORY`).
- `schemaVersion`·`eventId`·`eventType`·`publishedAt`은 OutboxWriter가 채웁니다. payload DTO에 넣으면 거부됩니다. payload는 JSON 객체, 전체 64KiB 이하입니다.
- 같은 `orderingKey`(예: `database:12`)의 이벤트는 생성 순서대로 발행됩니다. 재발행해도 eventId는 바뀌지 않으므로 소비자는 `ProcessedEventStore.markProcessed(stream, group, eventId)`를 업무 저장과 같은 트랜잭션에서 호출하고, 커밋 후 XACK합니다. false면 이미 처리한 이벤트입니다.
- `CollectorHeartbeatEvent`는 outbox를 쓰지 않고 Redis로 직접 발행합니다.
- 모든 `Instant`는 `YYYY-MM-DDTHH:mm:ss.SSSZ`(UTC, 밀리초 3자리)로 직렬화됩니다(`UtcInstantJacksonConfig`). 별도 `ObjectMapper` 빈을 만들지 말고 Spring이 주입하는 것을 사용하세요.
