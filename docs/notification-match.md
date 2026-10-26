# 최초 적합 요약·자격 전환 알림

## 범위와 결정 기록

PR #80은 찜 이후 변경 알림의 버전 기준, 판정 배치 뒤 후보 관측, FIRST_MATCH 요약과 TRANSITION Outbox,
Notification의 NEW_MATCH 수신·슬롯 편입을 연결한다. backend가 대상·근거·채널을 결정하고 Notification이
수신 설정·만료·탈퇴·Endpoint를 확인한다. 코드와 로컬 검증 결과이며 운영 활성화 승인은 아니다.

2026-10-26 확정 정책과 잠정 개발값을 다음과 같이 구분한다. 이 표가 이전 문서의 초기 적재·찜 기준선 설명을 대체한다.

| 항목 | 현재 결정 | 상태 |
| --- | --- | --- |
| 찜 이후 정책 변경 | 저장 버전과 같은 정책의 버전 번호를 비교해 `savedVersionNo < sourceVersionNo`일 때만 변경 알림 대상 | 확정 |
| `saved_version_id = null` | 버전을 추정하거나 첫 비교 버전을 저장하지 않고 정책 버전 변경 알림에서 제외. 신청 시작·D-7/3/1 등 날짜 알림은 유지 | 확정 |
| 기존 찜의 시작점 | 기존 null 행을 현재 버전으로 일괄 채우는 작업은 없음. 배포 시 1회 backfill 여부·시점은 별도 결정 | 미확정 |
| 첫 정책 버전 | 실제 첫 버전(`versionNo=1`, 이전 버전 없음)만 BASELINE_SKIPPED로 자동 기록. 변경 이력·이벤트·Outbox 생성 없음 | 확정 |
| 초기 적재 구분 | 전역 초기화·출처별 최초 적재 완료 계약을 이 알림 경로의 선행 조건으로 요구하지 않음. 이력 누락을 첫 버전으로 간주하지 않음 | 확정 |
| FIRST_MATCH | 사용자·정책별 최초 적합 후보를 기록하고 사용자별 요약 1건에 전건 귀속. 상위 N개만 기록하지 않음 | 확정 |
| TRANSITION | 실제 NOT_ELIGIBLE → ELIGIBLE 발생마다 1회. 같은 발생의 재처리만 중복 차단 | 확정 |
| 대상 | 청년정책의 ELIGIBLE 또는 기준 통과 POSSIBLY_ELIGIBLE. UNKNOWN·훈련과정 제외 | 확정 |
| POSSIBLY 기준 | 미해결 조건은 정보 없음 + 확인 필요의 합계. 초기 한도 1, 관심 분야 일치 필수 | 확정 정책의 초기 설정값 |
| 신청 상태 | OPEN·ALWAYS_OPEN 허용. UPCOMING은 찜한 정책만 허용하며 미저장 정책의 선제 안내는 제외 | 현재 개발 기준 |
| 요약 회차·만료 | 매일 KST 08:30~09:00 생성, 다음 날 09:00 만료. 시작 포함·끝 제외 | 팀 최종 확인 전 잠정값 |
| NEW_MATCH 푸시 순위 | BECAME_ELIGIBLE 뒤 priority 6 | 팀 최종 확인 전 잠정값 |
| 추천 페이지 | 실제 프론트 경로를 확인해 summary-link로 주입. 테스트 경로를 운영 기본값으로 사용하지 않음 | 경로 확인 필요 |

정책의 첫 버전 기준선과 사용자의 찜 버전은 서로 다른 기록이다. 첫 버전을 자동 기준선으로 만들더라도
기존 찜의 null을 채우거나 그 사용자에게 과거 변경을 소급 통지하지 않는다.
null 제한은 정책 버전 변경에 대한 것이다. 같은 정책 버전에서 프로필 변경으로 발생한 실제 전환까지 일괄 차단하지 않는다.

## 관측·후보·Outbox 흐름

1. `EligibilityBatchRefresher`가 사용자별 최신 판정 갱신을 마친 뒤 `EligibilityResultsRefreshed(userId)`를 발행한다.
   갱신 건수가 0이어도 성공 이벤트를 발행하며, 갱신 자체가 실패한 사용자는 제외한다.
2. `MatchBatchObservationListener`가 결과가 있는 현재 청년정책을 키셋 페이지로 읽고 `observe`를 호출한다.
   이전 관측 상태와 현재 입력 키를 비교하며 정책·프로필·규칙·신청 상태·찜·관심·미해결 조건 변화를 반영한다.
   판정/정규화 규칙 버전이 바뀐 관측은 최초 적합/전환 후보 생성을 억제한다.
3. 쌍 상태와 후보를 같은 트랜잭션에서 저장한다. FIRST_MATCH는 occurrence 0, TRANSITION은 발생 번호를 증가시킨다.
   불가→가능의 실제 전환은 FIRST_MATCH보다 우선하며, 가능 상태 유지·같은 입력 재처리는 새 발생이 아니다.
4. `MatchAutomaticWorker`가 PENDING 전환 후보와 요약 대상 사용자를 제한된 페이지로 처리한다.
   Outbox 직전 최신 입력과 찜 상태·버전·신청 상태·규칙을 다시 확인한다. 오래된 전환은 제외하고
   신선한 판정/관측이 필요한 후보는 DEFERRED로 남긴다.
5. Outbox와 후보 ENQUEUED 전환을 같은 트랜잭션에서 저장한다. 기존 Publisher와 Notification 수신·슬롯 경로가 전달한다.

최초 적합은 사용자·정책별 기록이며 요약은 사용자별 회차다. 같은 사용자가 서로 다른 날 새 정책을 처음 발견하면
새 회차 요약을 받을 수 있다. 회차 ID는 `(KST 날짜, 사용자)`로 결정하므로 같은 날 재시도는 같은 Outbox를 반환하고
나중에 생긴 후보를 이미 보낸 요약에 끼워 넣지 않는다. 창을 놓친 미발행 후보는 다음 날까지 PENDING으로 남는다.

TRANSITION의 sourceEventId는 발생별 후보 event_id다. 발생 시각부터 7일 뒤 만료하며 재시도로 연장하지 않는다.
찜하지 않은 정책의 자격 상실·추가 확인 알림은 만들지 않는다. 기존 찜 경로의 상실/추가 확인 IN_APP 요청은 유지한다.
`match.outbox.enabled=true`이면 기존 명시/자동 자격 Fan-out은 새 BECAME_ELIGIBLE 생성을 MATCH_ROUTE_OWNS_TRANSITION으로 양보한다.
기존에 생성된 Outbox를 자동 삭제·변환하거나 서로 다른 설정의 혼합 인스턴스 중복을 해결하는 기능은 없다.

## 새 테이블과 멱등성

이름에 notification이 들어가지만 두 테이블 모두 backend 소유의 **application 스키마**에 있다.
Notification이 직접 읽지 않으며 notification 스키마와 FK를 만들지 않는다.

| 테이블 | 저장 내용 | 주요 제약·정리 |
| --- | --- | --- |
| notification_match_pairs | 사용자·정책의 마지막 판정 상태/입력 키/버전·규칙·프로필, 최초 적합 이력, 전환 발생 번호 | UNIQUE(user_id, opportunity_id). 사용자 삭제 시 CASCADE |
| notification_match_candidates | FIRST_MATCH/TRANSITION 발생, UUID event_id, 원본 근거 JSON, 후보 상태, outbox_id와 종료 사유 | UNIQUE(pair_id, reason, occurrence_no), UNIQUE(event_id), INDEX(status,id). 쌍 삭제 시 CASCADE |

후보 상태는 PENDING → ENQUEUED 또는 SKIPPED다. DEFERRED·BUDGET_EXCEEDED는 호출 결과이며 후보를 종료하지 않는다.
ENQUEUED는 Outbox 생성 완료를 뜻하고 실제 인앱 저장/FCM 성공을 뜻하지 않는다.
요약에 포함된 모든 후보가 같은 outbox_id를 참조하며 전체 정책 목록을 Notification DB에 복제하지 않는다.
기존 Outbox와 수신 측 `(userId, sourceEventId)` 멱등성도 유지한다.

## 설정 11개

| 키 | 기본값 | 의미·범위 |
| --- | --- | --- |
| yon.tracking.match.enabled | false | 관측 서비스·배치 성공 리스너 활성화. 아래 관측 비용 P2 해결 전에는 비활성 유지 |
| yon.tracking.match.possibly.max-unresolved | 1 | 미해결 조건 합계 한도, 0 이상 |
| yon.tracking.match.possibly.require-interest | true | POSSIBLY에 관심 분야 일치 요구 |
| yon.tracking.match.summary-link | 빈 값 | 검증된 내부 추천 페이지 경로. match.enabled=true이면 필수. 외부 URL·query·fragment 불허 |
| yon.tracking.match.outbox.enabled | false | 명시/자동 요약·전환 Outbox 경로. match.enabled=true가 선행 조건 |
| yon.tracking.match.outbox.max-summary-candidates | 500 | 사용자 한 요약 트랜잭션의 후보 상한, 1~500 |
| yon.tracking.match.automatic.enabled | false | 자동 회차 Worker 등록. match.enabled와 outbox.enabled도 필요 |
| yon.tracking.match.automatic.scheduler-enabled | false | 자동 타이머 등록 동의 |
| yon.tracking.match.automatic.max-transitions-per-run | 25 | 한 poll의 전환 후보 수, 1~500 |
| yon.tracking.match.automatic.max-summary-users-per-run | 25 | 한 poll의 요약 사용자 수, 1~500 |
| yon.tracking.match.automatic.poll-interval-ms | 60000 | 실행 종료 후 다음 poll 간격, 최소 1000ms |

공통 `app.scheduling.enabled`의 기존 기본값은 true다. 자동 회차 Scheduler는 이 옵션과 관측·Outbox·자동 Worker·scheduler-enabled를 모두 요구한다.
관측은 기존 판정 배치 이벤트에서 실행되므로 automatic 옵션만 꺼도 match.enabled=true이면 관측 비용이 발생한다.
새 스케줄러를 꺼도 기존 Publisher가 이미 커밋된 Outbox를 전달할 수 있다. 전달·Consumer·FCM은 각각 기존 게이트를 따른다.

### 회차 처리량과 사용자별 예산

단일 인스턴스·기본값에서 30분 창 × 분당 25명은 대략 **750 사용자 처리 시도/일** 규모다.
fixedDelay에 실행 시간이 더해지고, 전환 처리·잠금 대기·DEFERRED/실패 재처리도 시간을 사용하므로 완료 사용자 수 보장은 아니다.
사용자별 후보가 500개를 초과하면 일부만 발행하지 않고 BUDGET_EXCEEDED로 전건 보류한다.
다음 날에도 상한을 초과하면 자동 해소되지 않으므로 후보 집계/분할 정책을 정해야 한다.
활성화 전 PENDING 사용자 수·후보 수·실제 완료율·poll 시간·다음 날 이월량을 측정해 예산/간격을 조정한다.

## NEW_MATCH 계약·슬롯 우선순위

공유 JSON은 [notification-new-match-v1.json](../contracts/notification-new-match-v1.json)이다.
이 표본의 `/test-recommendations`와 고정 만료 시각은 테스트 값이며 실제 경로/운영 만료 정책이 아니다.

- schemaVersion=1, type=NEW_MATCH, channels=[IN_APP,PUSH]. 사용자 수신 설정에 따라 실제 채널이 줄어들 수 있다.
- payload.reason=FIRST_MATCH, summary=true, summaryRunId=sourceEventId인 UUID, evaluatedOn은 날짜다.
- matchCount는 양수 정수이며 eligibleCount + possiblyCount와 같아야 한다. maxUnresolved도 정수로 전달한다.
- POSSIBLY가 포함되면 `possiblyLabel`은 현재 계약상 정확히 `확인 필요 N개`다. backend가 생성하고 Notification이 검증하므로
  이 문구를 바꿀 때 생산자·소비자·공유 표본·계약 테스트의 배포 호환성을 함께 변경해야 한다. 이번 문서 보완은 검증을 완화하지 않는다.
- envelope의 opportunityId/opportunityVersionId는 대표 정책이다. 전체 요약 구성은 Application 후보의 outbox_id 관계로 보존한다.
- Notification에는 정책 건수만큼이 아니라 요약 인앱 1건·푸시 대기 항목 1건으로 저장한다.
- NEW_MATCH 자체의 link는 summary-link다. 여러 알림을 슬롯에서 묶은 푸시의 링크는 기존 inbox-path 규칙을 따른다.
- 우선순위: D-1(0) → D-3(1) → 신청 시작(2) → 마감 연장(3) → D-7(4) → BECAME_ELIGIBLE(5) → NEW_MATCH(6).

## 기존 DB와 배포·활성화 순서

1. 신규 match 활성화 플래그를 false로 유지하고 대상 DB·스키마·계정·백업을 확인한다. 기존 찜 null 행은 그대로 보존한다.
2. backend는 기존 `ddl-auto:update` 정책으로 `application.notification_match_pairs`와
   `application.notification_match_candidates`를 생성한다. 생성 후 UNIQUE·INDEX·FK의 실제 상태와 계정 권한을 확인한다.
   Notification은 계속 validate이며 backend update로 notification 스키마를 변경하지 않는다.
3. 기존 슬롯 DB는 Notification을 중지하고 notification_user로
   [notification-new-match-priority.sql](../database/scripts/notification-new-match-priority.sql)을 검토·적용한다.
   이 스크립트는 기존 priority CHECK의 범위를 0~5에서 0~6으로 바꾸며 알림 행을 삭제하지 않는다.
   기존 제약의 이름/대상 열이 다르면 자동 삭제하지 않고 실패한다. 새 DB는 수정된
   [notification-push-slots.sql](../database/scripts/notification-push-slots.sql)에 0~6이 포함되므로 이 추가 전환은 불필요하다.
   기존 DB에 테이블 생성용 SQL을 다시 적용하지 않는다.
4. **Notification DB 제약 → NEW_MATCH 지원 Notification → 생산 backend 활성화** 순서를 지킨다.
   구 Notification은 NEW_MATCH를 거부한다. backend를 먼저 배포하더라도 신규 생성 플래그는 비활성이어야 한다.
5. 실제 추천 페이지를 확인해 모든 backend 인스턴스에 summary-link를 설정한다. 관측만 켜더라도 빈 링크면 기동 실패한다.
6. 아래 관측 비용 P2를 수정·검증한 뒤 match.enabled 활성화를 검토한다. 기존 쌍/후보·구 경로 Outbox 상태와
   모든 인스턴스의 설정 일치를 확인한다. 기존 pending 메시지의 일괄 삭제·후보 초기화를 자동 수행하지 않는다.
7. 관측을 검증한 뒤 outbox.enabled를 켜고 automatic/scheduler는 비활성인 상태에서 명시 호출로 수신 계약·근거·Outbox를 확인한다.
   그 뒤 automatic/scheduler 게이트를 단계적으로 적용한다.
   팀의 잠정 회차·우선순위 확인, 실제 슬롯·동의·FCM 전달 검증과 기존 레거시 전환 절차는 별도 수행한다.

### 활성화 전 필수 후속: P2 관측 비용

**현재 미해결이다.** 리스너는 판정 배치 스레드에서 동기로 사용자별 대상 정책을 끝까지 순회한다.
각 observe가 정책·사용자·입력 잠금을 잡은 뒤 입력 키를 비교하므로 결과가 바뀌지 않아도 정책마다 트랜잭션을 연다.
예를 들어 대상 정책 4천 건이면 사용자당 최대 수천 번의 잠금 트랜잭션이 발생한다. 배치 기본 fixedDelay는 10분이며
관측 시간이 배치 실행 시간에 포함되므로 실행 주기도 길어진다.

후속 PR에서 잠금 없는 페이지 조회로 완전한 입력 키를 먼저 비교해 다른 경우만 observe를 호출하거나,
갱신 대상만 관측하고 날짜에 따른 신청 상태 변화를 별도 순회하는 방법을 검토한다.
버전·프로필만으로 생략하면 찜·관심·미해결 수·규칙 설정·신청일 경계 변화를 놓칠 수 있다.
최종 저장 시 기존 잠금 아래 재검증은 유지하고, 무변경 입력의 잠금 감소·동시 변경·날짜 경계·실패 재시도를 검증해야 한다.
단순 비동기 실행으로 옮기는 것만으로 전체 DB 작업량이 줄지는 않는다. 해결·부하 검증 전 match.enabled=false를 유지한다.

### 중지·미결정 항목

- 자동 회차 중지는 automatic.scheduler-enabled=false로 제어한다. 관측 중지는 match.enabled도 내려야 하며
  outbox.enabled=true와 match.enabled=false 조합은 기동 가드가 거부한다. 기존 커밋 Outbox/전달 중지는 별도다.
- outbox.enabled를 끄면 기존 찜 경로가 새 BECAME_ELIGIBLE 생성 책임을 다시 가지므로, 혼합 배포·잔여 후보·Outbox를 검토한 뒤 전환한다.
- 기존 null 찜의 1회 backfill, 실제 추천 경로, 잠정 회차·만료·NEW_MATCH 순위, 대량 사용자/500개 초과 처리 정책은 별도 확인한다.
- WAITING_POLICY 재개·보류 마감 자동 재스캔·외부 FCM 전달 검증은 이 문서 추가로 완료되지 않는다.

## 검증 결과

2026-10-26 PR #80 기능 커밋 `a1711a5`를 최신 main 기반으로 통합한 작업 트리에서 실행한 결과다.

| 실행 | 결과 |
| --- | --- |
| backend: YON_TEST_DB_URL을 테스트 전용 yon_test로 지정해 `test bootJar --rerun-tasks` | 960개 중 958개 통과, 벤치마크 2개 건너뜀, 실패·오류 0. bootJar 성공 |
| notification-service: `test --rerun-tasks` | 189개 통과, 건너뜀·실패·오류 0 |

문서 보완은 코드를 바꾸지 않는다. 실제 업무 DB의 DDL 적용·타이머 장시간 실행·운영 부하·외부 FCM은 미검증이다.
로컬 테스트 통과는 위 관측 비용 P2 해결이나 운영 활성화 승인을 뜻하지 않는다.
