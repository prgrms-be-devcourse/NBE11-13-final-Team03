# 정책 버전 추적 — 10단계

## 현재 범위

10단계는 PARTIAL이다. 구조적 snapshot 비교기와 Application 소유의 버전 처리 기록/변경 이력 모델을 구현했다.
기준선 초기화·자동 스케줄러는 아직 연결하지 않았다. 사용자별 순회는 별도 옵션을 요구하는 명시적 경로로 연결했다.
잠정 알림 매핑·명시적 사용자별 Outbox 경로와 순차 비교 실행 경로는 아래 범위를 따른다.
버전 비교 기록과 마감 연장 이벤트·기존 Outbox 취소는 아래 명시적 호출 트랜잭션으로 연결했다.
새 테이블은 backend의 기존 ddl-auto:update 정책을 따른다. 실제 운영 DB 적용은 미검증이다.
실제 `.env`·전달·Consumer·푸시 플래그는 변경하지 않았다. Notification의 지원 타입은 아래 계약 절을 따른다.

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
| PROCESSED_WITH_DEFERRED_DEADLINE | 자격조건 후속 작업은 영속화해 다음 버전으로 진행 가능. 마감 판단은 미완료로 별도 보존 |
| PROCESSED | 버전 단위 이벤트 판단/필요 후속 작업 저장까지 완료. 사용자별 판정·발송 완료와 별개 |

변경 발견만으로 버전을 처리 완료로 간주하지 않는다. READY_FOR_EVENT_ANALYSIS는 processed_at이 없고,
이벤트 판단과 필요한 이벤트 저장이 커밋되는 트랜잭션에서만 완료/진행 상태로 바꾼다.
마감만 보류된 상태의 processed_at은 순차 진행 체크포인트이며 마감 해결을 뜻하지 않는다.
명시적 분석 트랜잭션에서 근거·후속 이벤트와 완료 전환을 함께 저장한다. 이를 자동 실행하는 스케줄러는 연결하지 않았다.

`opportunity_changes`는 (from_version_id, to_version_id) UNIQUE이며 실제 구조 차이가 있을 때만 저장한다.
서로 다른 정책·역순/동일 버전 비교를 거부한다. 정규화 규칙 변경과 실제 정책 변경이 동시에 발생하면
해당 버전의 사용자 알림은 생략될 수 있다는 기존 설계의 한계를 유지한다.

## 재사용·미확정 범위

- 기존 EligibilityEvaluator.judge를 동일 프로필·기준일의 버전 비교에 재사용한다.
- 비교에 저장된 과거 EligibilityResult를 쓰거나 비교 결과로 최신 결과를 덮어쓰지 않는다.
- 기존 EventFanout/Outbox와 날짜 이벤트는 유지하며 새 이벤트 타입 지원·계약 표본을 먼저 확장한 뒤 생성한다.
- 최초 출처의 정규화 실패/재시도에 대한 기준선 완료 조건과 판정 상태 쌍별 알림 매핑은 확정 전 자동 구현하지 않는다.
- 훈련 시작일·사업기간을 신청기간으로 취급하지 않고 복수 기간의 연장 여부가 불명확하면 임의 확정하지 않는다.

## 기존 병합 범위 검증 (2026-10-25)

| 테스트 | 건수 | 결과 |
| --- | --- | --- |
| OpportunitySnapshotComparatorTest | 10 | skip/failure/error 0 |
| OpportunityVersionTrackingTest | 6 | skip/failure/error 0 |
| OpportunityVersionTrackingPostgresTest | 8 | skip/failure/error 0 |
| EligibilityVersionComparisonServiceTest | 6 | skip/failure/error 0 |
| DeadlineExtensionDetectorTest | 14 | skip/failure/error 0 |
| DeadlineExtensionPostgresTest | 15 | skip/failure/error 0 |

- 기존 마감 임박·Fan-out·의존성 경계 및 양쪽 메시지 계약 테스트 통과.
- Notification의 계약/HTTP 수신 테스트 33개는 skip/failure/error 0. 새 타입 저장과 재전달 멱등성 포함.
- backend 전체 테스트·bootJar 성공. 기존 조건부 PostgreSQL 테스트 19개는 별도 실행하여 skip/failure/error 0 확인.
- notification-service 전체 테스트·bootJar 성공, 전체 skip/failure/error 0 확인.
- PostgreSQL에서 JSONB 보존·UNIQUE·동시 중복 실행·변경 이력/처리 기록/연장 이벤트/취소의 원자적 롤백을 검증했다.
- 자동 Worker, 연속 버전의 전체 처리 체인, 실제 운영 DB·AWS 전달·FCM 발송은 이번 검증 대상이 아니다.

## 동일 입력 신청 가능성 비교

EligibilityVersionComparisonService는 프로필을 한 번 조회하고 기존 EligibilityEvaluator에 이전·변경 버전 ID를
한 번에 전달한다. 두 버전에 같은 프로필 입력·현재 판정 규칙·명시적으로 전달된 기준일이 적용된다.
결과는 버전 ID, 프로필 버전, 규칙 버전, 기준일 및 두 판정의 상태·근거·신청 상태를 포함한다.
EligibilityResult 캐시를 조회하거나 갱신하지 않는다. 프로필이 없으면 결과 없음으로 반환하며 UNKNOWN을 만들어 넣지 않는다.
서로 다른 정책·역순/동일 버전·정규화 규칙이 다른 비교는 거부한다.

이 비교 결과는 발송 승인이 아니다. 상태 쌍별 알림 매핑, 최신 프로필·정책 재검증 및 근거 영속화는 후속 연결 대상이다.

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


## 버전 비교 기록과 마감 연장 처리의 원자적 연결

OpportunityVersionComparisonTransaction.compare(toVersionId)는 기존 기준선/완료된 직전 버전이 있는
YOUTH_POLICY 버전을 명시적으로 비교한다. 현재 포인터까지의 과거 버전도 허용하며 현재 포인터 뒤의 버전은 처리하지 않는다.
첫 버전의 초기 적재 여부를 추측하거나 기준선을 자동 생성하지 않는다.

- 정책 행 잠금 후 대상 버전의 기존 처리 기록이 있으면 그 기록을 그대로 반환한다. 재실행으로 기준선이나 분석 대기를 완료로 바꾸지 않는다.
- 구조 차이가 있으면 OpportunityChange를 저장하고, 무변경이어도 OpportunityVersionTracking을 저장한다.
- 정규화 규칙 변경은 NORMALIZATION_VERSION_CHANGED로 기록하며 실제 구조 차이는 이력으로만 보존한다.
- 같은 규칙의 변경은 기존 DeadlineExtensionTransaction을 REQUIRED 전파로 호출한다.
  변경 이력·버전 기록·연장 이벤트·이전 PENDING 요청 취소가 한 트랜잭션에서 함께 커밋/롤백된다.
- 무변경/규칙 변경 억제는 종료 상태다. 실제 변경은 자격조건 분석이 끝난 것이 아니므로 READY_FOR_EVENT_ANALYSIS로 남긴다.
  직전 버전이 아직 분석 대기이면 다음 버전 비교는 거부하며 건너뛰지 않는다.

순차 비교와 명시적 이벤트 분석 완료 경로는 아래 절을 따른다. 자동 스케줄러·신규 출처 초기 적재 계약은 미연결이다.

## 결정·협의 체크리스트 (2026-10-25)

### 선택한 방향 — 구현 완료와 구분

추적 담당 결정: 모든 정책 버전을 버전 번호 순서로 비교·기록하고, 실제 사용자 알림은 최신 정책에서도
의미가 유지되는지 재검증한다. 중간 버전을 단순히 버려 이력을 생략하는 방식을 기본값으로 삼지 않는다.

예: 마감이 10/25 → 10/30 → 10/27로 바뀌면 두 변경 이력을 모두 남기되, 최신 마감과 맞지 않는
“10/30까지 연장” 요청은 만들지 않는다. 이미 발행된 요청의 회수는 기존 MVP 범위 밖이다.

현재 포인터까지의 과거 버전을 순서대로 찾는 명시적 Worker 경로를 추가했다.
분석·완료는 별도의 명시적 트랜잭션으로 연결했지만 자동 호출자는 없으므로 전체 자동 처리 체인 완료와는 구분한다.
모호한 신청기간은 분석 대기를 유지하며 사용자별 판정·순회/복구와 초기 적재 계약을 자동 활성화 전에 연결해야 한다.

### 추적 담당 결정·확인 사항

| 항목 | 현재 결정 | 남은 구현/확인 |
| --- | --- | --- |
| 버전 처리 방향 | 모든 버전을 순차 비교·기록 | 과거 버전의 순차 비교 경로 추가, 이벤트 분석·완료/자동 복구 연결 필요 |
| 오래된 변경의 알림 | 최신 정책에서도 의미가 유효할 때만 요청 | 사용자별 판정 변화의 최신 프로필·정책 재검증 연결 |
| 초기 적재의 변경 알림 | 최초 적재분은 늦게 정제되어도 기준선으로 처리하는 방향 | 수집·정제 담당자와 구분 정보·초기화 완료 조건 협의 |
| 미확정 정책 | 초기 적재 계약·판정 상태별 매핑은 미확정으로 유지 | 확정 전 자동 연결하지 않음 |

### 팀원과 협의할 항목 — 아직 합의되지 않음

| 협의 대상 | 확인할 질문 | 확인이 필요한 이유 |
| --- | --- | --- |
| 수집·정제 담당 | 과거 버전과 각 버전의 조건·신청기간이 보존되는가? 보관/정리 제한은? | 순차 비교와 과거 버전 판정의 전제 확인 |
| 수집·정제 담당 | 초기 적재분·일반 갱신분을 정책 버전에 어떻게 연결할 것인가? | 원본/요청/수집 실행과 버전의 정확한 연결 확보 |
| 수집·정제 담당 + 추적 담당 | 실패·재처리 중인 최초 적재분의 초기화 완료를 무엇으로 판단할 것인가? | 복구된 최초 적재분의 오발송 방지. 실패 복구 책임 자체는 data-service 유지 |
| 판정 정책 담당 + 추적 담당 | ELIGIBLE/NOT_ELIGIBLE/POSSIBLY_ELIGIBLE/UNKNOWN의 각 상태 쌍을 어떤 알림 유형에 대응할 것인가? | 가능해짐·자격 상실·추가 확인 필요의 의미와 채널 결정 |

### 추적 담당의 후속 설계 — 임의 완료 처리 금지

- 실제 변경 분석에서 어떤 단계까지 끝나야 PROCESSED로 전환하는가?
- 판정 정책이 미확정이거나 기간 대응이 불명확할 때 대기·재검토를 어떻게 관리하는가?
- 순차 분석 진행 상태와 사용자별 이벤트/Fan-out 진행 상태를 어떻게 구분하는가?
- 연속 버전 생성·재시작·실패 복구 시 이력 누락과 중복 알림을 어떻게 방지하는가?

SUPERSEDED 종료 상태 도입은 이번에 확정하지 않았다. 모든 버전을 기록한다는 선택과 처리 완료 상태의
세부 규칙은 별개이며, 기존 READY 상태를 편의상 PROCESSED로 바꾸거나 직전 기록 검사를 삭제하지 않는다.

### 운영 활성화 전 별도 재검토

버전 추적 외에도 기존 미확정 항목인 푸시 슬롯·묶음/빈도 제한, Tombstone 보관 기간·비활성 Endpoint 정리,
SQS Visibility/재전달 횟수/보존 기간·DLQ 복구, 배포/IAM·FCM 자격증명 및 실제 발송 검증은 별도로 남아 있다.
이 목록에 적었다는 이유만으로 운영값이나 발송 활성화가 승인된 것은 아니다.

## 10단계 후속 — 개발용 잠정 계약 (팀 협의 전)

위 미확정 기록은 팀 합의 여부에 대한 것으로 유지한다. 후속 개발에서는 다음 잠정안을 사용하도록 추적 담당이 승인했다.
이는 팀 정책 확정·운영 활성화 승인이 아니다. 팀 정책 협의에서 항목별 확정/수정/보류를 검토한다.

### 판정 상태 매핑: development-provisional-v1

| 이전 상태 | 변경 상태 | 개발용 분류 | 요청 채널 |
| --- | --- | --- | --- |
| NOT_ELIGIBLE | ELIGIBLE | BECAME_ELIGIBLE | IN_APP, PUSH |
| ELIGIBLE | NOT_ELIGIBLE | ELIGIBILITY_DOWNGRADED | IN_APP |
| ELIGIBLE | POSSIBLY_ELIGIBLE | ELIGIBILITY_REVIEW_REQUIRED | IN_APP |
| 동일 상태 | 동일 상태 | NO_CHANGE | 없음 |
| 그 외 변화 | 그 외 변화 | DEFERRED | 없음 |

POSSIBLY_ELIGIBLE과 UNKNOWN은 같은 의미가 아니다. 특히 ELIGIBLE → UNKNOWN을 자격 상실이나
추가 확인 필요로 임의 변환하지 않는다. 프로필/판정이 없는 것도 UNKNOWN으로 대체하지 않는다.
DEFERRED는 정책 미확정에 따른 보류이며, NO_CHANGE나 버전 분석 완료를 뜻하지 않는다.

ProvisionalEligibilityTransitionClassifier는 상태 쌍만 받는 순수 분류기다. 입력은 같은 프로필·판정 규칙·기준일의
버전 비교 결과여야 하며, 실제 요청 생성 전 최신 프로필·정책에서 의미를 재검증해야 한다.
명시적 Outbox와 아래 사용자별 Fan-out 경로에서 이 분류기를 사용한다. 자동 실행 스케줄러는 미연결이며,
분류 결과에 요청 채널이 있어도 분류기 자체가 발송을 수행하지 않는다.
실제 채널은 기존 계약대로 사용자 수신 설정과의 교집합을 적용한다.

### 초기 적재와 개발 범위

- 초기 적재분은 늦게 복구돼도 기준선으로 처리하고 초기 변경 알림을 생략하는 잠정 방향을 유지한다.
- 초기 적재 여부가 불명확하면 임의로 일반 갱신으로 보지 않고 판단 대기로 둔다.
- 구체 필드·저장 위치·초기화 완료 조건은 data-service 담당자와 협의 전이다. 실패 복구 코드는 변경하지 않는다.
- 모든 버전의 순차 비교·분석 완료·복구는 여전히 후속 구현이다. 최신 재검증은 아래 명시적 저장 경로에 연결했다.
- 이번 승인에 11단계 푸시 슬롯·묶음·횟수 정책은 포함하지 않는다.

### 후속 기능 검증 — 최종 상태

| 신규 테스트 | 건수 | 결과 |
| --- | --- | --- |
| ProvisionalEligibilityTransitionClassifierTest | 20 | skip/failure/error 0 |
| EligibilityNotificationRevalidationServiceTest | 12 | skip/failure/error 0 |
| EligibilityNotificationRevalidationPostgresTest | 17 | skip/failure/error 0 |
| EligibilityNotificationMessageFactoryTest | 5 | skip/failure/error 0 |
| EligibilityOutboxConfigurationTest | 2 | skip/failure/error 0 |

- 신규 56개가 모두 통과했다. 기존 동일 입력 비교·의존성 경계 테스트도 통과했다.
- backend 전체 테스트·bootJar 성공. 기존 조건부 PostgreSQL 19개는 별도 실행해 skip/failure/error 0 확인.
- Notification 계약 7개·수신 PostgreSQL 28개가 skip/failure/error 0이며 전체 테스트·bootJar도 성공했다.
- 별도 리뷰 검증: backend 742개 실패 0·조건부 건너뜀 98, notification-service 95개 실패/건너뜀 0.
- 속성명 변경 재검증 대상: 기본 비활성화 2개·명시적 활성화를 사용하는 PostgreSQL 통합 테스트 17개.
- 실제 업무 DB·운영 전달·FCM 발송과 순차 Worker의 검증 완료를 뜻하지 않는다.

### 최신 프로필·정책 재검증 — 읽기 근거 반환

EligibilityNotificationRevalidationService는 기존 비교 근거와 명시적인 재검증 기준일을 받는다.
최신 프로필을 한 번 조회하고 이전/변경/현재 버전을 한 번의 EligibilityEvaluator 호출로 판정한다.
현재 버전과 변경 버전이 같으면 해당 버전 ID는 중복 평가하지 않는다.

- 원래 비교가 지원된 잠정 후보이며, 최신 프로필로 다시 비교해도 같은 알림 유형이 나와야 한다.
- 최신 정책의 판정 상태도 변경 버전의 재판정 상태와 같아야 후보 의미가 유지된다.
- 프로필 변화로 이전 정책에서도 이미 가능해졌다면 현재 정책이 ELIGIBLE이어도 기존 “정책 변경으로 가능해짐” 후보는 유지하지 않는다.
- 프로필 없음·정책/버전 없음·판정 규칙 변경·정규화 규칙 변경·변화 의미 소멸을 구분해서 반환한다.
- 원본 비교와 최신 프로필로 재평가한 비교, 재검증 정책 버전 ID·판정 근거·잠정 매핑 정책 버전을 결과로 보존한다.
- 읽기 재검증 서비스 자체는 EligibilityResult 캐시의 조회/갱신이나 이벤트·Outbox 저장을 하지 않는다.
  명시적 Outbox 트랜잭션과 이를 재사용하는 사용자별 Worker가 호출하며 자동 스케줄러는 연결하지 않았다.

이 결과는 읽은 시점의 후보 근거이지 저장 시점까지 유효한 발송 승인이 아니다.
아래 명시적 Outbox 경로가 저장 여부·탈퇴·정책/프로필 동시 변경, 신청 상태·만료를 검사하고 근거를 함께 저장한다.
읽기 서비스 단독 호출은 그 잠금 보호를 대신하지 않는다. 실제 수신 채널은 Notification이 동의 설정으로 제한한다.

### 실제 PostgreSQL·판정기 통합 검증

EligibilityNotificationRevalidationPostgresTest는 일회용 Testcontainers PostgreSQL과 실제 Repository·EligibilityEvaluator·EligibilityEngine을 사용한다.
정규화/수집 서비스를 import하지 않고 도메인 행으로 버전별 자격조건·신청기간·프로필을 준비한다.

- 실제 연령 조건의 NOT_ELIGIBLE → ELIGIBLE 변화와 근거 반환
- 최신 커밋된 프로필 변경, 최신 정책의 불충족/판정 불명에 따른 후보 무효화
- 비정형 조건의 추가 확인 필요, 정규화 규칙 변경 억제, 프로필 삭제 구분
- 생일이 지나 재검증 기준일이 바뀌는 경우의 동일 날짜 적용
- 일부러 다른 상태를 저장한 EligibilityResult 캐시가 비교를 지배하거나 덮어써지지 않음
- 읽기 서비스 단독 호출은 Outbox·이벤트·변경 이력·버전 기록을 생성하지 않음
- 명시적 저장 경로의 재실행·동시 중복·INSERT 실패 복구와 정책/사용자/저장/프로필 변경의 커밋 대기

동일 통합 테스트 클래스의 총 17개에서 읽기 판정과 Outbox 저장을 검증했다. 실제 업무 DB는 사용하지 않았다.

### 사용자별 Outbox — 잠정 계약의 명시적 저장 경로

EligibilityOutboxTransaction은 `yon.tracking.eligibility-outbox.enabled=true`인 경우에만 등록된다.
기본값/실제 설정은 비활성 상태이며, 통합 테스트에서만 속성을 지정한다. 이 옵션은 Publisher/Consumer/Push 플래그와 별개다.
자동 스케줄러에는 연결하지 않았다. 아래 명시적 소비 경로가 Outbox 트랜잭션을 재사용하며 날짜 Fan-out은 ELIGIBILITY_CHANGED를 점유하지 않는다.
새 경로는 올바른 버전 쌍을 가진 영속 ELIGIBILITY_CHANGED 이벤트를 명시적으로 전달해야만 실행된다.

- 이벤트 → 현재 정책 → 사용자 → 저장 상태 → 프로필 순서로 잠근다. 사용자 행은 탈퇴/저장 변경과 같은 FOR UPDATE,
  정책·저장 상태·프로필은 공유 잠금으로 재검증부터 Outbox 커밋까지 변경을 막는다.
- 저장·숨김을 함께 설정한 사용자도 대상이다. 사용자 없음·저장 해제·프로필 없음은 요청을 생성하지 않는다.
- 원본 이벤트의 정책/변경 버전과 from/to payload를 검증하고 최신 재검증을 같은 트랜잭션에서 실행한다.
- 보수적인 개발용 가드로 최신 신청 상태가 CLOSED/UNKNOWN이면 요청을 생성하지 않는다. OPEN/ALWAYS_OPEN/UPCOMING만 통과한다.
  이 세부 가드도 팀 정책 검토 대상이며, 판정의 ELIGIBLE과 신청 상태 OPEN을 같은 개념으로 취급하지 않는다.
- 같은 `(userId, sourceEventId)`의 재실행은 기존 Outbox ID를 반환한다. 수신 측 UNIQUE/소비 기록 계약은 그대로다.
- CANDIDATE일 때 사용자별 메시지와 원본/최신 비교의 버전·프로필·규칙·기준일·상태·설명, 매핑 정책 버전을
  Outbox envelope의 payload에 함께 저장한다. 원본 프로필 필드와 전체 EligibilityResult를 복제하지 않는다.
- 만료는 source event의 occurredAt + 7일이다. 재실행으로 만료를 연장하지 않으며 기준일이 처리 도중 바뀌면 롤백/재시도한다.
- 새 Notification 타입은 BECAME_ELIGIBLE / ELIGIBILITY_DOWNGRADED / ELIGIBILITY_REVIEW_REQUIRED이며,
  앞의 잠정 채널 매핑을 사용한다. 공유 대표 표본은 contracts/notification-eligibility-v1.json이다.

Outbox 생성과 근거 보존은 같은 행의 원자적 INSERT다. profile/정책/저장/탈퇴 경쟁은 이 명시적 트랜잭션 경로에서만
잠금으로 보호되며, 독립적인 읽기 재검증 호출 자체가 저장까지의 신선도를 보장하는 것은 아니다.
기존 Outbox Publisher와 Notification의 동의·Tombstone·Delivery 확인은 그대로 사용한다.
운영 활성화 전 Notification의 새 타입 지원을 먼저 배포하고 팀 정책·초기 적재 계약을 확정해야 한다.

### 다음 Worker PR의 선행 조건과 팀 협의

- **날짜 Fan-out 타입 분리:** APPLICATION_OPENED / APPLICATION_CLOSED / DEADLINE_SOON / DEADLINE_EXTENDED만
  후보 조회·claim·시도 소진 복구 대상으로 삼으며, 재시도도 같은 허용 목록으로 제한한다.
  ELIGIBILITY_CHANGED / OPPORTUNITY_CREATED는 PENDING 또는 오래된 PROCESSING 상태여도 이 Worker가 변경하지 않는다.
  메시지 생성기의 지원 목록을 공유하며, 지원 목록 고정·직접 점유 방어·외부 유형의 행 보존을 검증하는 테스트를 추가했다.
  관련 테스트 26개에서 skip/failure/error 0을 확인했다. 아래 명시적 분석 경로의 이벤트 생산과 별개이며 날짜 Worker는 이 이벤트를 소비하지 않는다.
- **잠정 상태 쌍:** POSSIBLY_ELIGIBLE → ELIGIBLE, NOT_ELIGIBLE → POSSIBLY_ELIGIBLE을 포함한 DEFERRED 쌍의 의미·채널을 팀과 합의한다.
- **신청 상태 가드:** CLOSED/UNKNOWN 차단 및 UPCOMING 통과 여부를 검토한다. 현재 허용 범위를 확정 정책으로 승격하지 않는다.
- **나머지:** 아래 명시적 비교·분석·사용자별 순회/lease 복구 외에 자동 스케줄러·초기 적재 계약·정책 보류 재개는 미연결이며 새 경로와 실제 발송은 비활성 상태를 유지한다.

## 순차 버전 비교 실행 경로

`OpportunityVersionWorker.runOnce(afterOpportunityId, maxPolicies)`는 자동 스케줄러 없는 명시적 실행 경로다.
정책 ID 키셋 페이지(최대 500개)와 정책별 한 버전 처리로 실행량을 제한한다. 반환한 커서로 다음 페이지를 이어가며,
한 순회가 끝나면 다음 순회는 0부터 시작한다. 커서는 버전 처리 완료 기록을 대신하지 않는다.

- 정책별 별도 트랜잭션에서 정책 행을 잠그고 현재 버전 포인터를 다시 확인한다.
- 현재 포인터까지 `processedAt`이 없는 가장 이른 버전을 선택한다. 누락 기록과 READY 상태 모두 포함하므로 중간 버전을 건너뛰지 않는다.
- 첫 버전의 기록이 없으면 WAITING_FOR_BASELINE, 이전 완료 근거가 없으면 WAITING_FOR_PREDECESSOR로 대기한다.
  새 기준선을 자동 추론하거나 수집·정제의 실패 복구를 대신하지 않는다.
- 기존 READY는 WAITING_FOR_EVENT_ANALYSIS로 반환한다. 이를 PROCESSED로 덮어쓰거나 다음 버전으로 넘어가지 않는다.
- NO_CHANGE / NORMALIZATION_VERSION_CHANGED는 기존 종료 규칙에 따라 다음 호출에서 후속 버전으로 진행한다.
- 과거 버전의 구조 변경도 이력을 남긴다. 기존 마감 연장 경로의 현재 버전 검증은 유지하므로 과거 연장을 새 알림으로 만들지 않는다.
- 비교 중 실패하면 그 정책 트랜잭션만 롤백한다. 다음 정책은 계속 처리하고 다음 순회에서 실패 버전을 다시 찾는다.
  별도 RUNNING 점유 행이 없어 비교 트랜잭션 중단 후 해제해야 할 lease도 없다. 자동 재시도 스케줄·Backoff는 아직 연결하지 않았다.
- 기준선 대기 정책도 커서를 전진시켜 다른 정책의 처리를 막지 않는다. 여러 실행자는 정책 행 잠금과 기존 UNIQUE로 직렬화한다.

날짜 Worker 타입 분리 테스트 26개는 skip/failure/error 0으로 확인했다.
순차 비교 경로는 OpportunityVersionWorkerTest 4개·DeadlineExtensionPostgresTest 23개·IngestBoundaryTest 3개에서 skip/failure/error 0을 확인했다.
그 이후 추가한 아래 분석 완료 경로의 테스트 결과와는 구분한다.
실제 DB DDL·환경변수·발송 플래그·스케줄 설정은 변경하지 않았다.

## 버전 분석 근거·후속 작업과 완료의 원자적 저장

`OpportunityVersionAnalysisTransaction.analyze(toVersionId)`는
`yon.tracking.version-analysis.enabled=true`일 때만 등록되는 명시적 개발 경로다. 속성이 없거나 false이면 등록하지 않는다.
실제 환경에 이 옵션을 추가하지 않았고 자동 Worker에서도 호출하지 않는다. 사용자별 Outbox 옵션·전달·푸시 옵션과 별개다.

- 정책 행을 잠근 뒤 현재 포인터·직전 버전 진행 근거·같은 정규화 규칙·READY/마감 보류 상태와 변경 이력을 확인한다.
- `opportunity_version_analysis`에 `(tracking_id, analysis_version)` UNIQUE, 분석 규칙 버전, 분석 당시 현재 버전 ID, 변경 경로,
  마감 분석 결과, 후속 이벤트 참조와 기록/완료 시각을 저장한다. backend의 기존 update 정책을 따르며 실제 DB 반영은 미검증이다.
- 신청기간은 행 ID·원문 표현을 제외하되 기간 유형·시작/종료일·검증 상태·scope·중복 개수를 보존해 비교한다.
  동일한 기간은 UNCHANGED다. 달라진 scope/복수·불명확 기간은 DEFERRED_SCOPED 또는 DEFERRED_PERIOD로 기록한다.
  마감 보류와 무관하게 자격조건 비교 이벤트를 저장하고 PROCESSED_WITH_DEFERRED_DEADLINE으로 순차 진행을 허용한다.
  이때 분석 이력의 completed_at은 NULL로 남는다. 마감 판단을 완료로 간주하거나 보류 근거를 지우지 않는다.
- 명확한 연장은 기존 마감 연장 경로를 사용한다. 과거 버전의 연장 근거는 남기지만 오래된 연장 알림은 새로 생성하지 않는다.
- 명확한 구조 변경에는 버전 쌍 단위 `ELIGIBILITY_CHANGED:{policyId}:{fromId}:{toId}` 후속 작업을 저장한다.
  이는 사용자별 판정을 비교하라는 요청이지 실제 신청 가능성 변화나 알림 발송의 확정이 아니다.
  제목 등 판정과 무관한 변경도 후속 판정에서 NO_CHANGE가 될 수 있다. 사용자별 DEFERRED를 완료로 바꾸는 규칙을 추가한 것은 아니다.
- 후속 작업의 occurredAt/eventDate는 비교 기록 시점을 보존한다. 늦은 분석이나 재실행으로 만료 기점을 새로 갱신하지 않는다.
- 분석 근거·필요 이벤트·완료/진행 상태 전환은 같은 트랜잭션에서 커밋/롤백한다. 실패 시 기존 READY 또는 마감 보류 체크포인트가 유지된다.
  재실행/동시 호출은 같은 분석 기록·이벤트를 반환하며 기존 이벤트의 버전 쌍이 충돌하면 완료를 거부한다.
- **완료 경계:** 버전 분석 완료는 후속 작업의 영속화까지다. 이벤트의 사용자별 순회·Outbox 완료는 별도 상태다.
  이번 경로에서 사용자를 평가하거나 Outbox를 만들지 않으며 이벤트는 PENDING으로 남는다.
- 같은 분석 규칙의 재실행은 같은 행을 반환한다. 분석 규칙 버전이 바뀌면 기존 마감 보류 버전에 새 이력을 추가할 수 있다.
  현재 분석 규칙은 development-version-analysis-v2이며 호출자가 임의 문자열을 지정하지 않는다.
  보류가 해결되면 PROCESSED로 전환하고, 해결되지 않으면 새 보류 근거와 진행 체크포인트를 유지한다.
  자격조건 이벤트는 버전 쌍 멱등키를 재사용하며 재분석으로 사용자 알림을 복제하거나 만료를 연장하지 않는다.

버전 분석 경로는 DeadlineExtensionPostgresTest 34개·OpportunityVersionAnalysisConfigurationTest 2개·
OpportunityVersionWorkerTest 4개·IngestBoundaryTest 3개에서 skip/failure/error 0을 확인했다.
아래 사용자 순회 경로의 신규 테스트 결과와는 구분한다. 실제 활성화 전 팀 계약과 운영 조건을 확인해야 한다.

## 저장 사용자별 판정·Outbox·중단 복구

EligibilityFanoutCoordinator / Batch / Worker는 `yon.tracking.eligibility-fanout.enabled`와
`yon.tracking.eligibility-outbox.enabled`가 **모두 true**일 때만 등록한다. 기본값은 비활성이며 실제 환경에는 추가하지 않았다.
`runOnce()` 명시 호출만 있고 자동 스케줄러는 없다. 페이지 크기·lease·재시도·이벤트 예산은 전용 `yon.tracking.eligibility-fanout.*` 설정을 사용한다.
기본 페이지는 25명이고 날짜 Worker 기본 500명과 독립적이다. 이 값은 초기 운영 설정이며 실제 잠금 시간·판정 비용 측정 후 조정해야 한다.
기존 날짜 스케줄러의 enabled 옵션으로 새 Worker가 실행되지는 않는다.

- 전용 Coordinator는 ELIGIBILITY_CHANGED만 후보 조회·claim·소진 복구·재시도한다. 날짜 Worker와 서로의 이벤트를 점유하지 않는다.
  잘못 전달된 다른 타입의 lease도 Batch에서 예외로 거부하며 이벤트를 FAILED로 변경하지 않는다.
- 이벤트 → 현재 정책 → ID 오름차순 사용자 → 저장 상태 → 프로필 순서로 잠근다. 숨김 여부와 관계없이 저장 사용자 ID 키셋을 읽는다.
  사용자 mutex를 잡은 뒤 저장·프로필을 확인하고 동일 프로필/규칙/기준일의 이전/변경 버전을 판정한다.
- 후보이면 기존 EligibilityOutboxTransaction을 같은 페이지 트랜잭션에서 호출해 최신 프로필·현재 정책·신청 상태·만료를 재검증한다.
  최신 정규화 규칙이 달라진 이벤트는 NORMALIZATION_RULE_CHANGED로 억제한다. EligibilityResult 캐시는 읽거나 덮어쓰지 않는다.
- `eligibility_event_assessments`는 `(event_id, user_id)` UNIQUE다. 원본/최신 판정 상태·설명, 프로필/규칙/정책 버전·기준일,
  매핑 버전·처리 사유·Outbox ID를 기록한다. 무변경·건너뜀·보류도 저장하며 원본 프로필 필드는 복제하지 않는다.
  사용자 FK ON DELETE CASCADE로 탈퇴 시 이 근거도 삭제한다. 실제 DB 적용과 보관 기간은 별도 검증/협의 대상이다.
- 근거·Outbox·커서·heartbeat는 페이지 단위로 함께 커밋/롤백한다. 실패한 페이지가 앞 페이지의 커밋을 취소하지 않는다.
  오래된 PROCESSING은 새 lease로 이어받아 마지막 커서 다음부터 재개하며 과거 lease의 저장/재시도는 거부한다.
- `(userId, sourceEventId)` Outbox 멱등성을 유지한다. 이미 생성한 명시적 Outbox도 재사용하며 재실행으로 근거를 덮어쓰지 않는다.
- 판정용 프로필 없음은 SKIPPED / PROFILE_MISSING으로 기록하고 다른 회원 처리를 계속한다.
  로그인 회원 정보(User)와 판정용 프로필(EligibilityProfile)은 별개다. 없는 입력을 UNKNOWN/NO_CHANGE로 만들거나 정책 보류로 묶지 않는다.
  나중에 프로필을 생성해도 해당 이벤트의 소급 알림을 보장하지 않는다.
- 잠정 매핑 DEFERRED, 최신 판정 불명 등은 사용자별 DEFERRED로 남긴다.
  페이지 커서는 전진시켜 나머지 사용자를 처리하되, 마지막 페이지 뒤 보류가 남으면 이벤트를 **WAITING_POLICY**로 둔다.
  이 상태는 processedAt/lease가 없으며 COMPLETED·FAILED가 아니다. 자동 후보/소진 복구 대상에서 제외한다.
  팀 정책 확정 후 보류 재평가·재개 절차는 아직 구현하지 않았다. 입력/매핑 불명을 전송 오류처럼 반복하거나 성공으로 승격하지 않는다.
- 보류 없이 순회가 끝나면 이벤트 COMPLETED다. 이벤트 만료(발생 후 7일)는 EXPIRED로 종료하고 만료를 연장하지 않는다.
  순회 중 새로 저장한 사용자 포함은 기존 키셋 정책처럼 보장하지 않는다.

리뷰 보완 후 로컬 검증 결과는 문서 마지막 검증 절을 따른다.
버전 비교 → 명시적 분석 → 명시적 사용자 Worker는 각각 호출 가능하지만 자동 주기 실행·초기 적재 분류는 미연결이다.
플래그·실제 DB·AWS/FCM·Notification 프로젝트 코드는 변경하지 않았다.

## 활성화 전 DB 제약·운영 확인

1. backend를 중지하고 대상 DB/계정·백업을 확인한다. 개인 테스트 이외의 DB에 임의 적용하지 않는다.
2. 배포 담당이 아래 조회로 현재 application 테이블의 제약을 확인한다.

```sql
SELECT c.conrelid::regclass AS table_name, c.conname, pg_get_constraintdef(c.oid)
FROM pg_constraint c
WHERE c.conrelid IN (
  to_regclass('application.opportunity_events'),
  to_regclass('application.opportunity_version_tracking'),
  to_regclass('application.opportunity_version_analysis'))
ORDER BY 1, 2;
```

3. 기존 테이블에는 application_user로 database/scripts/notification-version-worker-upgrade.sql을 검토 후 수동 적용한다.
   이 스크립트는 WAITING_POLICY와 PROCESSED_WITH_DEFERRED_DEADLINE을 허용하도록 enum CHECK를 바꾸고,
   분석 이력의 tracking_id 단독 UNIQUE를 규칙별 복합 UNIQUE로 전환한다. 기존 행을 삭제하지 않는다.
   알 수 없는 사용자 정의 CHECK/단독 UNIQUE 인덱스는 임의 삭제하지 않고 실패시킨다. 오류 시 전체 트랜잭션을 롤백한다.
4. 새 테이블이 없으면 새 backend를 **모든 신규 기능 비활성 상태**로 기동해 기존 update 정책으로 생성하고 제약/FK를 다시 확인한다.
   update가 기존 CHECK/UNIQUE를 바꿔 줄 것이라고 가정하지 않는다. 위 스크립트는 재적용 가능하며 테이블 자체를 생성하지 않는다.
5. 기준선 초기화·초기 적재 계약을 먼저 확인한다. 기준선이 없으면 WAITING_FOR_BASELINE인 것이 정상이며 활성화 완료가 아니다.
6. 비교 → 분석 → 사용자 순회를 7일 만료 내에 실행할 운영 절차를 검증한다. 만료 기점은 비교 기록 시점이며 연장하지 않는다.
7. 팀 미확정 상태 쌍·UPCOMING 가드·WAITING_POLICY 재개·마감 대응 규칙과 운영 관측을 합의하고 CI 성공 후 배포/활성화를 별도로 승인한다.

## 리뷰 보완 후 로컬 검증 (2026-10-25)

| 테스트 | 건수 | 결과 |
| --- | --- | --- |
| DeadlineExtensionPostgresTest | 36 | skip/failure/error 0 |
| EligibilityNotificationRevalidationPostgresTest | 31 | skip/failure/error 0 |
| OpportunityVersionTrackingTest | 7 | skip/failure/error 0 |
| OpportunityVersionWorkerTest | 4 | skip/failure/error 0 |
| EligibilityFanoutPropertiesTest | 3 | skip/failure/error 0 |
| VersionWorkerUpgradePostgresTest | 3 | skip/failure/error 0 |
| FailureDiagnosticTest | 2 | skip/failure/error 0 |
| IngestBoundaryTest | 3 | skip/failure/error 0 |
| **합계** | **89** | **skip/failure/error 0** |

- 프로필 없음 건너뜀/다음 사용자 처리, 마감 보류 후 순차 진행, 규칙별 재분석 이력·롤백·멱등성을 검증했다.
- 전용 페이지 기본값/설정 검증, 민감 메시지가 포함되지 않는 디버그 진단을 확인했다.
- DB 전환 SQL은 일회용 PostgreSQL에서 CHECK·UNIQUE 전환, 이력 보존, 재적용, 사용자 정의 제약 보호를 검증했다.
- 보완 후 `./gradlew test bootJar --rerun-tasks`가 성공했다. 전체 테스트에서 조건부 PostgreSQL 19개는 환경변수 미지정으로 건너뛰었다.
- 해당 19개를 테스트 전용 yon_test DB로 별도 재실행했다. EligibilityResultServiceTest 18개와 ConditionSetBackfillTest 1개 모두 skip/failure/error 0이다.
- 이 결과는 로컬 검증이며 추가 커밋 기준 CI 성공은 별도 확인해야 한다. 실제 DB에 전환 SQL을 적용하지 않았다.
- 실제 환경변수·자동 실행·전달/푸시 활성화는 변경하지 않았다. 기준선/초기 적재 계약·팀 미확정 정책·운영 배포는 후속이다.
