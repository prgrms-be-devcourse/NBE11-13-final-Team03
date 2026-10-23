# AGENTS.md

## Project

청년 지원정책을 수집하고 사용자 조건에 맞는
신규·변경·마감 임박 기회를 추적하여 알리는 서비스.

검색보다 추적과 변경 감지에 중점을 둔다.

## Architecture

- Backend: Spring Boot
- Frontend: React
- 영역: 수집, 정규화, 자격 판정, 이벤트, 알림
- 실제 설계 및 API 계약은 docs/를 확인한다.

## Domain Rules

- 외부 API 원본 데이터와 정규화 데이터를 구분한다.
- 출처와 외부 식별자를 보존한다.
- 중복 수집과 재실행 시 데이터 정합성을 유지한다.
- 불명확하거나 충돌하는 자격 조건을 임의로 확정하지 않는다.
- 정책 상태 변경과 이벤트 발생 기준을 구분한다.

## Development Rules

- YAGNI 원칙을 따른다.
- 기존 코드와 표준 기능을 우선 재사용한다.
- 요청 범위를 벗어난 변경을 하지 않는다.
- 불필요한 추상화와 새 의존성을 추가하지 않는다.
- 보안, 예외 처리, 데이터 정합성을 생략하지 않는다.
- 알림 기능은 `notification-service/` 프로젝트에만 둔다. `backend`에 `com.team3.backend.notification` 패키지를 만들지 않는다 (`ApplicationPersistenceCompletenessTest`가 검사한다).
- 수집·정규화는 `data-service/` 프로젝트에만 둔다 (`docs/ingest-split.md`). `backend`에는 판정·검색용 읽기 어댑터(opportunity.domain·dto, region.directory·domain)와 수집 관리자 API 전달(collection.controller·exception)만 두고, 정규화·수집 코드(opportunity.ingest·normalization·service·conditionset, region.client·service)를 다시 만들지 않는다 (`IngestBoundaryTest`가 검사한다). backend 테스트의 공고 데이터는 `OpportunityFixtures`로 만든다.

## Workflow

1. 요청과 관련된 코드 및 문서를 확인한다.
2. 변경 범위를 최소화하여 구현한다.
3. 영향을 받는 테스트를 실행한다.
4. 변경 내용과 검증 결과를 요약한다.
5. 실행하지 못한 테스트는 명확하게 알린다.
