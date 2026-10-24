# 정책 버전 추적 — 10단계

## 현재 범위

10단계는 PARTIAL이다. 구조적 snapshot 비교기와 Application 소유의 버전 처리 기록/변경 이력 모델을 구현했다.
자동 스캔·기준선 초기화·신청 가능성 변화의 알림 매핑/Outbox 연결은 아직 구현하지 않았다.
버전 비교 기록과 마감 연장 이벤트·기존 Outbox 취소는 아래 명시적 호출 트랜잭션으로 연결했다.
새 테이블은 backend의 기존 ddl-auto:update 정책을 따른다. 실제 운영 DB 적용은 미검증이다.
실제 `.env`·전달·Consumer·푸시 플래그는 변경하지 않았다. Notification은 DEADLINE_EXTENDED 타입 지원만 확장했다.

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

- 기존 EligibilityEvaluator.judge를 동일 프로필·기준일의 버전 비교에 재사용한다.
- 비교에 저장된 과거 EligibilityResult를 쓰거나 비교 결과로 최신 결과를 덮어쓰지 않는다.
- 기존 EventFanout/Outbox와 날짜 이벤트는 유지하며 새 이벤트 타입 지원·계약 표본을 먼저 확장한 뒤 생성한다.
- 최초 출처의 정규화 실패/재시도에 대한 기준선 완료 조건과 판정 상태 쌍별 알림 매핑은 확정 전 자동 구현하지 않는다.
- 훈련 시작일·사업기간을 신청기간으로 취급하지 않고 복수 기간의 연장 여부가 불명확하면 임의 확정하지 않는다.

## 검증 — 최종 로컬 상태 (2026-10-25)

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
현재 YOUTH_POLICY 버전을 명시적으로 비교한다. 첫 버전의 초기 적재 여부를 추측하거나 기준선을 자동 생성하지 않는다.

- 정책 행 잠금 후 대상 버전의 기존 처리 기록이 있으면 그 기록을 그대로 반환한다. 재실행으로 기준선이나 분석 대기를 완료로 바꾸지 않는다.
- 구조 차이가 있으면 OpportunityChange를 저장하고, 무변경이어도 OpportunityVersionTracking을 저장한다.
- 정규화 규칙 변경은 NORMALIZATION_VERSION_CHANGED로 기록하며 실제 구조 차이는 이력으로만 보존한다.
- 같은 규칙의 변경은 기존 DeadlineExtensionTransaction을 REQUIRED 전파로 호출한다.
  변경 이력·버전 기록·연장 이벤트·이전 PENDING 요청 취소가 한 트랜잭션에서 함께 커밋/롤백된다.
- 무변경/규칙 변경 억제는 종료 상태다. 실제 변경은 자격조건 분석이 끝난 것이 아니므로 READY_FOR_EVENT_ANALYSIS로 남긴다.
  직전 버전이 아직 분석 대기이면 다음 버전 비교는 거부하며 건너뛰지 않는다.

자동 Worker·신규 출처 초기 적재 계약·사용자별 판정 상태 매핑은 여전히 미연결이다.

## 결정·협의 체크리스트 (2026-10-25)

### 선택한 방향 — 구현 완료와 구분

추적 담당 결정: 모든 정책 버전을 버전 번호 순서로 비교·기록하고, 실제 사용자 알림은 최신 정책에서도
의미가 유지되는지 재검증한다. 중간 버전을 단순히 버려 이력을 생략하는 방식을 기본값으로 삼지 않는다.

예: 마감이 10/25 → 10/30 → 10/27로 바뀌면 두 변경 이력을 모두 남기되, 최신 마감과 맞지 않는
“10/30까지 연장” 요청은 만들지 않는다. 이미 발행된 요청의 회수는 기존 MVP 범위 밖이다.

이 방향은 다음 Worker/처리 체인 PR의 목표다. 현재 코드의 “현재 버전만 비교” 조건은 아직 그대로이며,
READY_FOR_EVENT_ANALYSIS를 완료로 바꾸는 자동 호출자도 없다. 따라서 현 코드가 순차 분석을 구현한 것은 아니다.
중간 버전 누락 또는 분석 대기로 정책 체인이 멈출 수 있으므로 자동 Worker 연결 전에 해결해야 한다.

### 추적 담당 결정·확인 사항

| 항목 | 현재 결정 | 남은 구현/확인 |
| --- | --- | --- |
| 버전 처리 방향 | 모든 버전을 순차 비교·기록 | 과거 버전 분석 허용, 진행 커서·복구·완료 조건 설계 |
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
