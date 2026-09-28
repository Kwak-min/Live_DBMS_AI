# Docs

## 팀 배포용 개발 기준 초안 v0.2

사용자의 위임에 따라 API·이벤트·보안·운영 정책을 구체적인 한 가지 기준과 기본값으로 정리했습니다. **아래 여섯 문서를 한 묶음으로 전달**합니다. ‘현재 코드’ 설명과 ‘구현할 목표 규격’을 구분하며 문서 작성으로 기능이 완료된 것은 아닙니다.

1. [전체 연동 규격](integration-contract-draft.md): 구조·기본값·현재 진행 현황·수정 대상
2. [REST API](api.md): 경로·권한·입출력·오류·필터·페이지·수신처
3. [Redis·STOMP·알림](events.md): 지표·DTO·상태 기계·전달·복구·외부 메시지
4. [인증·보안](integration-security.md): JWT·Refresh·CSRF·암호화·인가·주소 제한
5. [저장·운영](integration-operations.md): 내부 포트·테이블·마이그레이션·환경·보관·복구
6. [담당별 적용·검수표](integration-handoff.md): 전달 문구·개발 순서·A/B/C/프론트 작업·28개 검수 시나리오

[정상·실패·복구 메시지 예제 JSON](contract-examples.json): 프론트 mock과 계약 테스트의 입력으로 사용할 수 있는 가상 예제 10개.

## 기존 프로젝트 자료

- [백엔드 기능 요구사항](backend-functional-requirements.md): 요구사항 ID·검수 목적. 이전 수치·정책 미정 항목은 v0.2 규격으로 구체화했습니다.
- [백엔드 업무 분배](backend-responsibilities.md): A/B/C 역할과 데이터 흐름
- [협업 가이드](contributing.md): 브랜치·커밋·PR 규칙

규격 변경은 영향받는 생산자·소비자와 변경할 DTO·예제를 같은 PR에 기록합니다. 실제 배포 키·호스트·계정은 환경 입력으로 주입하고 문서/코드에 비밀을 적지 않습니다.

[프로젝트 소개](../README.md)
