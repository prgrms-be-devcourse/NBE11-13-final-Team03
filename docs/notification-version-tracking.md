# 정책 버전 추적 — 10단계

## 현재 범위

첫 검증 단위는 구조적 snapshot 비교기와 Application 소유의 버전 처리 기록/변경 이력 모델이다.
자동 스캔·기준선 초기화·신청 가능성 변화의 알림 매핑/Outbox 연결은 아직 구현하지 않았다.
버전 비교 기록과 마감 연장 이벤트·기존 Outbox 취소는 아래 명시적 호출 트랜잭션으로 연결했다.
새 테이블은 backend의 기존 ddl-auto:update 정책을 따르며 기존 운영 DB 적용·테스트 성공을 뜻하지 않는다.
실제 `.env`·전달·Consumer·푸시 플래그와 Notification 서버의 실행 코드는 변경하지 않는다.

## 비교 계약

- 이전/현재 normalizedSnapshot과 각각의 normalizationVersion을 입력받는다.
- JSON 객체 키 순서와 숫자 표현(19/19.0) 차이는 변경으로 보지 않는다.
- 누락과 명시적 null, 문자열/숫자/boolean, 신청기간/사업기간은 구분한다.
- 객체는 필드별로 비교하고 이력 경로는 JSON Pointer 방식으로 기록한다.
- 배열은 순서가 있는 전체 값으로 비교한다. 배열 재정렬 차이 자체가 알림 대상이라는 뜻은 아니다.
- 날짜를 추정하거나 기간을 임의 대응시키지 않는다. 마감 연장·판정 변화 해석은 후속 분석기의 책임이다.
- normalizationVersion이 다르면 실제 차이는 감사 이력으로 보존할 수 있지만 정책 변경 이벤트 분석은 억제한다.
- 동일 규칙·동일 snapshot도 NO_CHANGE 처리 기록을 남길 수 있다.
- 전역 ObjectMapper 설정에 의존하지 않고 입력을 변경하지 않는다. 숫자 표현을 통일한 불변 복사본을 결과로 사용한다.

## 기록 모델

`opportunity_version_tracking`은 opportunity_version_id UNIQUE다.

| 상태 | 의미 |
| --- | --- |
| BASELINE_SKIPPED | 별도 기준선 절차가 명시적으로 지정한 초기 적재 버전 |
| NO_CHANGE | 같은 정규화 규칙에서 구조 차이 없음 |
| NORMALIZATION_VERSION_CHANGED | 정규화 규칙 변경으로 이벤트 분석 억제 |
| READY_FOR_EVENT_ANALYSIS | 차이 발견, 이벤트 분석 미완료 |
| PROCESSED | 이벤트 판단/필요 이벤트 저장까지 완료 |

변경 발견만으로 버전을 처리 완료로 간주하지 않는다. READY_FOR_EVENT_ANALYSIS는 processed_at이 없고,
이벤트 판단과 필요한 이벤트 저장이 커밋되는 트랜잭션에서만 PROCESSED로 바꾼다.
현재 명시적 비교 서비스는 연결했지만, 이 완료 전환을 자동 실행하는 Worker는 연결하지 않았다.

`opportunity_changes`는 (from_version_id, to_version_id) UNIQUE이며 실제 구조 차이가 있을 때만 저장한다.
서로 다른 정책·역순/동일 버전 비교를 거부한다. 정규화 규칙 변경과 실제 정책 변경이 동시에 발생하면
해당 버전의 사용자 알림은 생략될 수 있다는 기존 설계의 한계를 유지한다.

## 재사용·미확정 범위

- 기존 EligibilityEvaluator.judge는 동일 프로필·기준일로 여러 버전을 판정할 수 있어 재사용 후보이다.
- 비교에 저장된 과거 EligibilityResult를 쓰거나 비교 결과로 최신 결과를 덮어쓰지 않는다.
- 기존 EventFanout/Outbox와 날짜 이벤트는 유지하며 새 이벤트 타입 지원·계약 표본을 먼저 확장한 뒤 생성한다.
- 최초 출처의 정규화 실패/재시도에 대한 기준선 완료 조건과 판정 상태 쌍별 알림 매핑은 확정 전 자동 구현하지 않는다.
- 훈련 시작일·사업기간을 신청기간으로 취급하지 않고 복수 기간의 연장 여부가 불명확하면 임의 확정하지 않는다.

## 검증

- OpportunitySnapshotComparatorTest: 키 순서·숫자 표현·null/누락·배열·규칙 변경·불변 복사·입력 검증
- OpportunityVersionTrackingTest: 기준선/무변경 기록, 분석 대기/완료 구분, 규칙 변경 억제와 버전 소유권 검증

위 단위 테스트 16개는 로컬 실행 성공이 확인됐다.

OpportunityVersionTrackingPostgresTest 8개도 로컬 실행 성공이 확인됐다. 일회용 PostgreSQL에서 다음을 검사한다.

- JSONB 저장/재조회 시 구조 차이·null/누락·배열·숫자 의미 보존
- 버전별 처리 기록 및 버전 쌍별 변경 이력 UNIQUE
- 한 트랜잭션의 후속 INSERT 실패 시 변경 이력/처리 기록 롤백
- 분석 완료 상태 변경의 커밋/롤백
- 동시 중복 트랜잭션의 단일 이력·기록 보장

현재 통합 테스트는 모델과 스키마 제약을 검증한다. 자동 비교 서비스/Worker의 중복 복구나 이벤트 생성·완료의 원자성을 검증했다는 뜻은 아니다.

## 동일 입력 신청 가능성 비교

EligibilityVersionComparisonService는 프로필을 한 번 조회하고 기존 EligibilityEvaluator에 이전·변경 버전 ID를
한 번에 전달한다. 두 버전에 같은 프로필 입력·현재 판정 규칙·명시적으로 전달된 기준일이 적용된다.
결과는 버전 ID, 프로필 버전, 규칙 버전, 기준일 및 두 판정의 상태·근거·신청 상태를 포함한다.
EligibilityResult 캐시를 조회하거나 갱신하지 않는다. 프로필이 없으면 결과 없음으로 반환하며 UNKNOWN을 만들어 넣지 않는다.
서로 다른 정책·역순/동일 버전·정규화 규칙이 다른 비교는 거부한다.

이 비교 결과는 발송 승인이 아니다. 상태 쌍별 알림 매핑, 최신 프로필·정책 재검증 및 근거 영속화는 후속 연결 대상이다.
EligibilityVersionComparisonServiceTest 6개는 로컬 실행 성공이 확인됐다.
이전 비교/모델 테스트와 합계 30개가 skipped/failures/errors 0이며 backend 전체 테스트,
기존 조건부 PostgreSQL 테스트 19개(skip 0), bootJar도 로컬 실행 성공이 확인됐다.
아래 마감 연장 감지 추가 전의 검증 결과다.

## 초기 적재 연동 경계

- 실패한 원본의 재시도·복구는 data-service의 기존 책임으로 유지한다.
- 초기 적재분은 늦게 정제되어도 초기 변경 알림을 억제하고 backend 기준선으로 기록하는 방향이다.
- data-service가 초기 적재 구분 정보를 제공하고 backend가 이를 적용한다. 정확한 전달 필드·저장 위치·초기화 완료 조건은 담당자 간 계약 확인 전이며 자동 연결하지 않는다.
- 현재 raw의 source/external ID와 수집 실행 정보는 있지만, raw와 정책 버전의 직접 연결은 저장하지 않는다. 최신 ingest 상태만으로 특정 버전의 생성 원본이나 복구 이력을 확정하지 않는다.
- 최초 버전 번호·처리 날짜·요청 FAILED 상태만으로 초기 적재 여부/완료를 추측하지 않는다.
- 초기 변경 알림 억제는 이후 실제 정책 변경이나 기존 날짜 알림을 영구 차단하는 의미가 아니다.

## 마감 연장 감지 — 단일 신청기간 비교

DeadlineExtensionDetector는 이전/변경 버전의 ApplicationPeriod 목록만 입력받는 순수 비교기다.
사업·훈련기간은 입력 대상이 아니다. 호출자는 같은 정책의 순방향 버전인지 검증하고 정규화 규칙 변경을 먼저 억제해야 한다.

- 양쪽에 기간이 정확히 하나씩 있으며 VALID/FIXED이고, 시작일·종료일이 존재하고 시작일 ≤ 종료일이어야 한다.
- 동일한 시작일에서 종료일이 뒤로 이동하면 EXTENDED와 이전/새 마감일을 반환한다.
- 대응 가능한 단일 기간의 종료일이 동일하거나 앞당겨지면 NOT_EXTENDED다.
- 기간 누락·복수 기간·시작일 변경/누락·종료일 누락·검증 불가·상시/마감/미상은 INCONCLUSIVE다. 연장이 없다는 확정이 아니다.
- 다른 기간을 먼저 제거하거나 최대 마감일·배열 순서·서로 다른 버전의 window ID로 기간을 임의 대응시키지 않는다.
- 현재 날짜로 과거 변경 이력을 제거하지 않는다. 현재 알림의 유효성·만료 판단은 이후 이벤트/Outbox 단계에서 수행한다.

동일 시작일의 단일 기간만 다루는 보수적인 첫 구현이며, 복수 기간이나 시작일이 없는 기간의 대응 규칙은 후속이다.
이 컴포넌트는 Worker에 연결하지 않았고 이벤트 생성·PENDING Outbox 취소·발송도 수행하지 않는다.
DeadlineExtensionDetectorTest 14개는 로컬 실행에서 skipped/failures/errors 0을 확인했다.
기존 DeadlineSoonDetectorTest 10개와 IngestBoundaryTest 2개도 함께 통과했다.

## 마감 연장 이벤트·Outbox 취소 연결

DeadlineExtensionTransaction.process(fromVersionId, toVersionId)는 명시적 호출용이다. 자동 스캔/Worker에는 연결하지 않았다.

- 정책 행 FOR UPDATE로 같은 정책의 중복 분석을 직렬화하고, FOR SHARE를 사용하는 기존 Fan-out과 현재 버전을 동기화한다.
- 현재 YOUTH_POLICY 버전과 바로 이전 버전만 비교한다. 정규화 규칙 변경·대상 버전의 BASELINE_SKIPPED는 이벤트를 만들지 않는다.
- 이전 버전의 기준선 또는 완료 기록이 없으면 거부한다. 이 검사는 신규 출처 초기 적재 계약 전체를 대신하지 않는다.
- scope가 있는 회차/지역 기간은 임의 대응하지 않는다. 최신 정책의 scope 변경도 Fan-out에서 제외한다.
- DEADLINE_EXTENDED:{opportunityId}:{fromVersionId}:{toVersionId}를 멱등키로 사용하고 이전/변경 버전·시작일·이전/새 마감일을 이벤트 payload에 저장한다.
- 이벤트 INSERT와 동일 정책·이전 마감일의 PENDING DEADLINE_SOON Outbox 취소를 한 트랜잭션에 저장한다.
  취소 시 last_error는 POLICY_DEADLINE_CHANGED다. 다른 날짜·정책·유형과 PUBLISHING/PUBLISHED/FAILED/SKIPPED는 변경하지 않는다.
- 기존 Outbox payload는 전체 envelope이므로 취소 SQL은 내부 payload.applicationEndDate를 검사한다.
- 다른 조건 변경 분석이 남을 수 있으므로 이 서비스가 버전 전체를 PROCESSED로 바꾸지는 않는다.

기존 Fan-out은 DEADLINE_EXTENDED도 저장 사용자(숨김 포함)에게 IN_APP/PUSH 요청으로 만든다.
최신 단일 VALID/FIXED 기간의 시작일·새 마감일이 여전히 같은지 확인하며, 만료는 occurredAt + 7일과
새 마감 다음 날 KST 00:00 중 빠른 시각이다. 이미 만료된 연장은 요청을 만들지 않는다.

공유 표본은 contracts/notification-deadline-extended-v1.json이다. Notification은 정책 비교 없이 새 타입을 수신·저장한다.
새 타입을 모르는 구 Notification은 거부하므로 운영 활성화 전 Notification 타입 지원을 먼저 배포해야 한다.
DB 컬럼·수동 DDL·실제 환경변수·전달/푸시 플래그는 변경하지 않았다.

DeadlineExtensionPostgresTest의 기존 7개는 로컬 실행에서 skipped/failures/errors 0을 확인했다.
양쪽 메시지 계약 표본, 기존 Fan-out의 연장/숨김 사용자 및 scope 재검증, Notification HTTP 저장·재전달 멱등성도 통과했다.
양쪽 전체 테스트·bootJar가 성공했고 Notification 전체 skip/failure/error 0,
backend의 기존 조건부 PostgreSQL 테스트 19개도 별도 실행에서 skip/failure/error 0을 확인했다.
다음 버전 비교 트랜잭션 추가 전의 결과다.

## 버전 비교 기록과 마감 연장 처리의 원자적 연결

OpportunityVersionComparisonTransaction.compare(toVersionId)는 기존 기준선/완료된 직전 버전이 있는
현재 YOUTH_POLICY 버전을 명시적으로 비교한다. 첫 버전의 초기 적재 여부를 추측하거나 기준선을 자동 생성하지 않는다.

- 정책 행 잠금 후 대상 버전의 기존 처리 기록이 있으면 그 기록을 그대로 반환한다. 재실행으로 기준선이나 분석 대기를 완료로 바꾸지 않는다.
- 구조 차이가 있으면 OpportunityChange를 저장하고, 무변경이어도 OpportunityVersionTracking을 저장한다.
- 정규화 규칙 변경은 NORMALIZATION_VERSION_CHANGED로 기록하며 실제 구조 차이는 이력으로만 보존한다.
- 같은 규칙의 변경은 기존 DeadlineExtensionTransaction을 REQUIRED 전파로 호출한다.
  변경 이력·버전 기록·연장 이벤트·이전 PENDING 요청 취소가 한 트랜잭션에서 함께 커밋/롤백된다.
- 무변경/규칙 변경 억제는 종료 상태다. 실제 변경은 자격조건 분석이 끝난 것이 아니므로 READY_FOR_EVENT_ANALYSIS로 남긴다.
  직전 버전이 아직 분석 대기이면 다음 버전 비교는 거부하며 건너뛰지 않는다.

자동 Worker·신규 출처 초기 적재 계약·사용자별 판정 상태 매핑은 여전히 미연결이다.
추가 PostgreSQL 테스트 8개(기존 클래스 총 15개)는 아직 실행 전이며 원자적 저장/롤백·무변경·규칙 변경·기준선 보호·동시성·분석 대기를 검사한다.
