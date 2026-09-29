# Stage 1 계약·fixture 인계 기록

상태: 기술 계약과 공유 fixture의 Stage 1 검증을 완료한 기록. 백엔드 런타임 구현·검증 및 2~7단계 완료를 뜻하지 않는다.

## 기준과 범위

- 기준 개발 브랜치: `origin/develop` / `f774c8df2950a376369bd2408609af1715ad92e7`.
- 기존 v0.2 문서 묶음은 문서 커밋 `a8f31c9`가 병합 커밋 `f774c8d`를 통해 develop에 반영되어 저장소 전역 공통 개발 기준으로 채택되어 있다. 이 기록은 그 규격을 보완한다.
- 원격 `origin/feature/be-auth`는 `0934d31a637c14221e47c8d2973b0a66a95ea2fc`이며 PR [#2](https://github.com/Kwak-min/Live_DBMS_AI/pull/2)로 열려 있지만 develop에는 병합하지 않는다.
- 기준 main `a7ca0d7568551733a03ac2e6327c4b3adc6cd5f7`은 범위에서 제외한다.
- 이번 변경은 feature 브랜치에 커밋·push까지만 진행하며 develop 병합과 PR 생성은 보류한다.
- 이번 인계는 문서·fixture·계약 검증 명령의 공유 기준만 다룬다. 개별 팀원의 승인·서명·수락은 별도 기록이 없으며, 이를 추정하거나 외부 게시를 주장하지 않는다.

## Stage 1 계약 폐쇄 규칙

- 알림 cooldown은 `eligibleAt = 마지막 성공 개시/상승 알림 시각 + notificationCooldownSeconds`로 계산한다. 같은 사건·수신처의 최신 상승으로 병합해도 처음 정한 `eligibleAt`은 미루지 않으며, 외부 발송 창은 `expiresAt = eligibleAt + 600초`다. FATAL 및 RECOVERED 예외는 기존 이벤트 규격을 따른다.
- 정책 변경은 metric 규칙에서 발생한 OPEN 사건만 `POLICY_CHANGED`로 종료하고, CONNECTION_FAILURE·COLLECTION_STALE 같은 시스템 사건과 그 타이머는 보존한다.
- 현재 configVersion에서 accepted collection이 한 번도 없는 대상은 `activationAt` 기준으로 `now < activationAt + staleAfterSeconds` 동안 `NO_DATA`, 정확히 경계에 도달하면 `STALE`이다. accepted collection이 있으면 마지막 accepted `collectionAttemptTime`을 기준으로 최신성을 계산해 `FRESH` 또는 `STALE`을 표시한다. `STALE`을 정상 `FRESH`로 표시하지 않는다.
- 동일 `eventId` 또는 이미 처리한 metric/version의 중복·지연 입력은 무시한다. 무시된 입력은 상태·지속/복구 타이머·알림 eligibleAt을 바꾸지 않는다.

## 공유 fixture 시나리오

fixture는 정상 0 값과 부분 실패(null 및 `unavailableMetrics`)를 구분하고, 중복·역순·낮은 configVersion 입력의 무시 결과를 명시한다. 경계 시나리오는 cooldown 만료 전/시각 도달, stale 유예 전/후, 정책 변경의 metric/system 사건 분리를 포함한다. 각 이벤트는 원본 `eventId`·`metricId`·버전과 순서를 보존해 producer/consumer가 같은 기대값을 사용한다.

담당자는 다음 순서로 사용한다: A는 fixture 입력·metric/outbox ID를 생산하고, B는 대상/configVersion 및 공통 인증·저장 경계를 확인하며, C는 상태·위험·사건·알림 기대값을 판정한다. 프론트는 null을 0으로 치환하지 않고 `NO_DATA`·`STALE`과 버전/중복 결과를 표시한다. 이는 역할별 적용 기준이며 개별 완료 승인 기록은 별도로 없다.

## 검증 방법과 완료 기준

반복 가능한 검증 명령은 `node scripts/validate-contracts.mjs`이다. 최종 fixture 담당 결과는 문법 검사와 기본·명시 경로 검증이 성공하고, malformed/semantic-null/semantic-STOMP/stale 변형을 exit 1로 거부하며, 기준 fixture 10/10 보존과 임시 입력 정리를 확인했다. 이 결과는 백엔드 런타임 동작을 증명하지 않는다.

완료 기준 중 계약 문서와 [공유 fixture](contract-examples.json)의 링크·필드 의미, 정상 0·부분 실패·중복/지연·cooldown·STALE·정책 종료 시나리오를 검증했다. 실제 결과는 다음과 같다.

```text
$ node scripts/validate-contracts.mjs
VALID: <repository>/docs/contract-examples.json fixtures=18 scenarios=9
$ node scripts/validate-contracts.mjs docs/contract-examples.json
VALID: <repository>/docs/contract-examples.json fixtures=18 scenarios=9
$ node --check scripts/validate-contracts.mjs
exit=0
$ git diff --check -- docs/README.md docs/stage-1-status.md
exit=0
```

출력의 로컬 경로는 공개 기록에서 `<repository>`로 표시했다.

malformed JSON, semantic null reason, semantic STOMP mapping, stale-boundary 변형은 각각 exit 1로 거부되었고, 기준 fixture는 `PRESERVED_FIXTURES=10/10`, 임시 입력은 `TEMP_INPUTS_CLEAN=TRUE`였다. 이 검증은 계약 fixture와 문서 공백만 확인하며, 원격 develop 병합이나 런타임 구현·테스트·배포, main 변경을 완료로 표시하지 않는다.
