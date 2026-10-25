# 11단계 푸시 슬롯·묶음 — 개발용 잠정 v1

## 승인 범위와 현재 구현

추적·알림 담당은 2026-10-25 아래 기준으로 개발하는 것을 승인했다. 팀 전체의 최종 정책 합의나 운영 활성화 승인은 아니다.
실제 환경변수·업무 DB·전달/Consumer·푸시 플래그는 바꾸지 않는다.

`pushExpiresAt` 계약/영속화/발송 전 재검사와 순수 슬롯 규칙에 이어 사용자별 대기·슬롯 묶음·사용 기록·기기별 묶음 Delivery·창 종료 정리를 연결했다.
미시도 묶음의 다음 슬롯 재예약을 연결했다. 이월 회귀 테스트·실제 타이머·외부 FCM 검증은 별개이며 실제 푸시는 계속 비활성으로 유지한다.
기존 즉시 Delivery와 새 슬롯 Worker는 설정으로 하나만 등록한다. 슬롯 모드에서는 수신도 새 대기열만 사용한다.
아래의 첫 PR/둘째 PR은 구현 경계이며, 검증 단위마다 별도 PR을 생성한다는 의미가 아니다.

## 잠정 정책

| 상황 | 개발 기준 |
| --- | --- |
| 수신 | 기존 채널·만료 기준으로 인앱 개별 즉시 저장. 푸시만 사용자별 대기 |
| 시간대/하루 | 서버 시간대와 무관하게 KST 고정, 하루는 KST 00:00 기준 |
| 슬롯 창 | 09:00~09:30, 13:30~14:00, 17:30~18:00. 시작 포함·끝 제외 |
| 호출·재시도 | claim과 FCM 호출 직전 모두 창 검사. 기존 지수 백오프 유지, 창 밖 신규 호출 금지. 진행 중 호출 완료 시각까지 보장하지 않음 |
| 횟수 | 사용자당 슬롯별 1개 묶음·하루 최대 3개. 화면 수신 횟수 보장이 아님 |
| 묶음 | 대표 1건 + 외 N건. 단건은 기존 상세 링크, 다건은 확인된 알림함 경로. 프론트 경로는 미확정이며 임의 생성하지 않음 |
| 우선순위 | D-1 → D-3 → 신청 시작 → 마감 연장 → D-7 → BECAME_ELIGIBLE. 동순위는 오래된 순, 동일 시각은 ID로 결정 |
| 우선순위 책임 | Notification이 type·payload.threshold로 계산. 별도 priority 필드는 추가하지 않음. 추천·기타는 v1 제외 |
| 푸시 대상 | backend가 요청한 PUSH와 사용자 동의/활성 Endpoint의 교집합. DOWNGRADED·REVIEW_REQUIRED는 기존 IN_APP 전용 |
| 만료 | 기존 메시지/인앱 만료 유지. D-N 푸시만 임계일 다음 날 00:00 KST를 pushExpiresAt으로 지정. 기존 메시지 만료를 넘기거나 이월로 연장하지 않음 |
| 발송 직전 검사 | 만료·동의·탈퇴·활성 Endpoint 재확인. 포함 알림은 제외만 가능하며 대표·건수·문구·링크 재계산. 전부 제외 시 발송 없이 종료 |
| 다기기 | 기기별 전달, 사용자 묶음 1회 계산. 무효 토큰은 해당 Endpoint만 비활성화 |
| 횟수 시작 | 어느 기기든 최초 발송 시도 시작 시 1회. 결과 성공/실패/불명 모두 포함. 같은 묶음 재시도 추가 차감 없음 |
| 전부 제외 | 어느 기기에서도 시도 시작 전 제외됐다면 미차감 종료. 이미 시도했다면 횟수 유지 |
| 멱등성 | 묶음 식별자 고정·중복 편입 금지. (사용자, KST 날짜, 슬롯) UNIQUE와 기기별 lease를 함께 사용. 외부 FCM 정확히 한 번 호출 보장은 아님 |

기존 초안보다 마감 연장 우선순위를 D-7 앞에 추가했으며, 신청 가능성 변화 표기는 실제 PUSH 요청 대상인 BECAME_ELIGIBLE로 한정했다.
Application이 priority를 싣는 이전 초안은 v1에 적용하지 않는다. 최초 계약 변경은 pushExpiresAt 추가로 한정한다.

## 최초 시도·예약과 장애

슬롯 예약과 횟수 차감을 구분한다. 첫 기기의 발송 시작 전환과 슬롯 사용/최초 시도 기록을 같은 DB 트랜잭션으로 커밋한 뒤 FCM을 호출한다.
커밋 직후 프로세스가 중단되면 실제 호출 전이어도 보수적으로 시도한 것으로 계산한다.
복구하면서 지워질 수 있는 현재 SENDING·lease만으로 미시도 여부를 추정하지 않는다. 최초 시도 시작 기록을 별도로 보존한다.
슬롯 UNIQUE만으로 외부 중복 호출이 차단되는 것은 아니며 기존 기기별 lease·재시도 정합성을 유지해야 한다.

## 창 종료·미시도 묶음 이월

- PR #73의 슬롯·창·묶음·횟수 제한에 미시도 이월을 추가한다. 기존 버전에서 이미 SKIPPED로 종료한 묶음은 자동 복구하지 않는다.
- READY이며 first_attempt_at이 없고 사용 기록·SENDING/SENT·장애 횟수도 없는 묶음만 이월한다. CLAIMED는 외부 호출 전 점유이므로 이월 가능하지만 기존 token은 무효화한다.
- 묶음 식별자·편입 관계는 유지하고 예약 날짜/슬롯/창만 변경한다. 새 알림·새 기기를 합치거나 제외된 전달을 되살리지 않는다.
- 창 사이에는 다음 슬롯을 예약하고, 늦게 복구했을 때 현재 창이 열려 있으면 그 창을 사용한다. 놓친 창을 순서대로 따라 발송하지 않는다.
- 다음 슬롯 시작 시각까지 만료될 알림을 제외하고 대표를 교체한다. 발송 직전에도 만료·동의·탈퇴·기기를 재검사한다. 전부 제외되거나 기존 활성 기기가 없으면 미차감 종료한다.
- 사용자 mutex → 묶음 → 전달 잠금 아래 예약 변경과 CLAIMED 해제/next_retry_at 변경을 한 트랜잭션으로 처리한다. 신규 묶음 생성도 같은 사용자 mutex 아래 오래된 미시도 묶음을 먼저 확인해 이월을 추월하지 않는다.
- 이월 묶음이 다음 슬롯의 자리를 사용하며 같은 슬롯에 새 묶음을 추가 발송하지 않는다. 기존 예약과 충돌하면 덮어쓰거나 합치지 않고 다음 빈 슬롯까지 대기한다.
- 예약만으로 횟수를 차감하지 않는다. 첫 기기 시도 시점의 KST 날짜/슬롯에 사용 기록을 남기므로 다음 날 이월은 실제 시도한 날의 1회다. 계속 미시도이면 만료 범위 안에서 반복 이월할 수 있다.

아직 묶이지 않은 유효 대기 알림은 다음 슬롯에서 묶는다. 시도했거나 결과 불명인 묶음은 이월/재묶음하지 않는다.
개발용 잠정 정책 구현과 팀 최종 합의·운영 검증은 구분하며, 이월까지 검증하기 전 전체 정책 완료·푸시 활성화로 해석하지 않는다.

## pushExpiresAt 계약·DB·배포 순서

- schemaVersion 1의 선택 필드다. DEADLINE_SOON 생성 시 backend가 eventDate의 다음 날 00:00 KST를 싣는다. receipt/createdAt으로 임계일을 추정하지 않는다.
- 수신 DTO·notifications.push_expires_at에 보존한다. 필드 누락/null은 기존 expiresAt을 사용하고, 유효 푸시 만료는 두 값의 최솟값이다.
- 푸시만 만료돼도 유효한 IN_APP은 생성한다. PUSH만 요청했고 푸시가 만료됐으면 CHANNEL_DISABLED 소비 결과를 기록한다. 전역 EXPIRED와 구분한다.
- 현재 수신 enqueue 검사와 Delivery prepare/FCM TTL 경로에도 유효 푸시 만료를 적용한다. 큐·슬롯 연결 전에도 인앱 만료를 줄이지 않는다.
- 필드 누락/null의 기존 소비 fingerprint는 유지한다. non-null pushExpiresAt은 알려진 계약 값으로 fingerprint에 포함되며 같은 messageId의 값 변경은 충돌이다.
- 기존 예제 contracts/notification-requested-v1.json은 그대로 보존하고 신규 계약 예제 notification-requested-push-expiry-v1.json을 추가한다.
- 새 DB의 notification-delivery.sql에는 nullable 컬럼을 포함한다. 기존 DB는 Notification 중지·백업·계정/대상 확인 후 notification_user로 notification-push-expiry.sql을 추가 적용한다. 이 스크립트는 반복 가능하며 이전 행의 null은 보존한다. 기존 delivery SQL을 다시 적용하지 않는다.
- **DDL → 새 Notification → 새 backend** 순서로 배포한다. 구 Notification은 새 필드를 무시할 수 있지만 새 만료를 지키지 못하고 fingerprint도 다르므로 안전한 롤링 호환으로 간주하지 않는다.
- 새 필드 메시지를 처리한 뒤 구 Notification으로 롤백할 때는 소비 fingerprint·만료 해석 차이를 검토한다. 기존 소비 기록을 임의 삭제하지 않는다.
- 기존 D-N 메시지/DB 행에 필드가 없으면 현재는 기존 만료 동작을 유지한다. 슬롯 활성화 전 해당 레거시 대기 Delivery의 정리/전환을 반드시 결정한다. 생성 시각 기반 자동 backfill은 하지 않는다.

## 슬롯 모드 설정·트랜잭션·전환

| 설정 | 기본값 | 동작 |
| --- | --- | --- |
| yon.notification.push.slots.enabled / NOTIFICATION_PUSH_SLOTS_ENABLED | false | 수신 경로·Worker 모드 선택 |
| yon.notification.push.slots.inbox-path / NOTIFICATION_PUSH_INBOX_PATH | 빈 값 | 슬롯 모드에서 필수. 확인된 내부 알림함 경로만 허용 |
| yon.notification.push.slots.users-per-run | 10 | 한 poll의 묶음 생성 사용자/만료 묶음 정리 상한 |
| yon.notification.push.slots.items-per-bundle | 100 | 묶음당 최대 편입 건수, 1~500. 남은 미편입분은 다음 슬롯 후보 |
| yon.notification.push.batch-size | 10 | 기존 기기별 전달·stale lease 복구 예산 |

- slots 옵션만 켜고 push.enabled=false이면 수신은 대기열을 사용하지만 자동 FCM Worker/Firebase는 등록되지 않는다.
  자동 실행은 두 옵션과 공통 app.scheduling.enabled를 모두 요구한다. 공통 제어 false에서는 테스트용 명시 호출만 가능하다.
- 기존 notification_deliveries를 삭제·이전하거나 임의 재편입하지 않는다. 슬롯 Store 생성 시 기존 PENDING/SENDING이 하나라도 있으면 기동을 거부한다.
  이 검사는 스케줄러 등록 전에 수행한다. 운영에서는 모든 구/신 Sender를 중지하고 백업·기존 전달 정리 방침을 확인한 뒤 모드를 전환한다.
  기존 Store의 수동 claim/prepare/recover도 슬롯 모드에서는 중단한다. 혼합 버전 Sender의 동시 운용은 지원하지 않는다.
- notification-push-expiry.sql 뒤에 notification-push-slots.sql을 notification_user로 한 번 적용한다.
  새 SQL은 기존 즉시 Delivery를 변경하지 않고 네 테이블을 만든다. 자동 DDL 적용·업무 DB 실행은 하지 않았다.
- 수신의 Notification·대기 항목·ConsumedMessage는 같은 트랜잭션이다. 유효한 legacy D-N에 pushExpiresAt이 없으면 인앱은 유지하되
  슬롯 큐에는 LEGACY_DEADLINE_PUSH_EXPIRY_MISSING으로 제외한다. 이미 소비한 메시지의 채널/기기를 소급 추가하지 않는다.
- 사용자 advisory lock → 묶음 행 → 기기별 전달 행 순서다. 생성·prepare·종료 처리는 탈퇴/설정/Endpoint 변경과 같은 사용자 mutex를 사용한다.
  claim/stale 복구는 전달 행의 원자적 점유만 수행하고 그 뒤 사용자/묶음 잠금을 잡지 않는다.
- prepare는 CLAIMED인 전달과 사용자/창/동의/기기/포함 알림 만료를 재검사한다. 최초 시도 기록·slot_usage·SENDING 전환을 같은 트랜잭션으로 저장한다.
  그 커밋 후에만 FCM을 호출한다. 슬롯 예약은 bundle 행, 횟수 사용은 slot_usage 행으로 구분한다.
- (사용자,KST 날짜,슬롯)의 예약 UNIQUE와 사용 UNIQUE, (묶음,Endpoint) UNIQUE로 중복 생성을 막는다.
  슬롯 번호는 1~3이며 날짜별 최대 세 슬롯이다. 모든 기기가 미시도로 제외되어도 예약한 슬롯에 다른 묶음을 새로 만들지는 않는다. 횟수 사용은 0이다.
- 같은 순위는 Notification.created_at과 id 순이다. 포함된 알림은 발송 직전 제외만 가능하며 새 항목을 넣지 않는다.
  각 기기의 준비 시점에 대표/건수가 달라질 수 있으며, FCM tag는 대표 ID 대신 고정 bundle ID를 사용한다.
- stale CLAIMED는 시도 기록/장애 횟수 없이 재점유한다. stale SENDING은 기존 지수 백오프/횟수 소모를 적용하고 최초 시도 기록은 보존한다.
  과거 token의 완료·무효 토큰 처리로 새 lease/Endpoint 상태를 변경할 수 없다.
- 창 밖에서는 새 점유/발송을 금지하고 PENDING/CLAIMED 및 lease가 만료된 SENDING만 예산 내에서 SKIPPED로 정리한다.
  창 종료·동의 철회 때도 lease 유효한 SENDING은 이미 FCM을 호출했을 수 있으므로 token을 보존하고 완료/실패 콜백을 허용한다.
  그동안 묶음은 READY로 남을 수 있지만 창 밖 신규 점유는 불가하며, 미완료 콜백 없이 lease가 만료되면 후속 poll에서 종료한다.
  결과는 SENT가 있으면 COMPLETED,
  SENT 없이 FAILED가 있으면 FAILED, 나머지는 SKIPPED로 집계한다. 정리는 여러 poll이 걸릴 수 있어도 창 밖 claim은 허용하지 않는다.
- 아직 묶이지 않은 만료 대기는 poll당 최대 1000개를 CAS로 제외한다. 인앱 행은 삭제하지 않는다.
- 묶음은 대표 Notification FK에 연결하고 대기 항목/기기별 전달/사용 기록도 Notification·Endpoint 삭제에 CASCADE로 정리한다.
  탈퇴 Tombstone/소비 기록은 기존 정책대로 남고, 삭제된 묶음을 늦은 완료 콜백으로 재생성하지 않는다.
- 이월에는 기존 네 테이블을 사용하며 추가 DDL은 없다. 현재 예약은 bundle 행을 이동해 표현하고 과거 미사용 예약을 별도 이력으로 쌓지 않는다. 실제 시도 이력이 있는 묶음의 예약/사용 기록은 이동하지 않는다.
  슬롯 모드를 다시 끌 때 새 큐/묶음이 구 Worker로 자동 이관되지 않는다.
  중지·잔여 데이터/호환성 확인 없이 플래그 토글이나 스키마 삭제로 롤백하지 않는다.

## 검증 상태와 후속

### 병합된 PR #73 검증 (2026-10-25)

| 테스트 | 건수 | 결과 |
| --- | --- | --- |
| NotificationSlotPostgresTest | 23 | skip/failure/error 0 |
| NotificationSlotWorkerTest | 6 | skip/failure/error 0 |
| NotificationPushConfigTest | 7 | skip/failure/error 0 |
| FirebasePushSenderTest | 5 | skip/failure/error 0 |
| PushSlotPolicyTest | 6 | skip/failure/error 0 |
| NotificationMessageContractTest | 10 | skip/failure/error 0 |
| NotificationDeliveryPostgresTest | 17 | skip/failure/error 0 |
| backend DateNotificationMessageFactoryTest | 13 | skip/failure/error 0 |
| backend IngestBoundaryTest | 3 | skip/failure/error 0 |
| **합계** | **90** | **skip/failure/error 0** |

- 리뷰 보완 전 양쪽 프로젝트의 `test bootJar --rerun-tasks` 성공. 리뷰 검증 보고 기준 Notification 전체 139개 실패/건너뜀 0, backend 전체 835개 실패 0·기존 조건부 건너뜀 98이다. 아래 보완 후 대상 테스트 재실행과 전체 실행 결과는 구분한다.
- 관련 조건부 테스트는 테스트 전용 yon_test에서 별도로 실행했다. EligibilityResultServiceTest 18개와 ConditionSetBackfillTest 1개 모두 skip/failure/error 0이며, 나머지 조건부 테스트의 실행을 뜻하지 않는다.
- backend 최초 전체 실행의 OpportunityDetailPostgresTest는 PostgreSQL SSL 연결 오류로 Context 초기화가 실패했다. 해당 클래스 단독 및 전체 재실행은 통과했다. dialect 설정이나 업무 코드는 변경하지 않았다.
- 리뷰 보완 후 NotificationSlotPostgresTest 23개와 NotificationSlotWorkerTest 6개를 재실행하여 총 29개 모두 skip/failure/error 0을 확인했다. 창 종료·동의 철회 시 진행 중 SENDING의 영수증 보존과 lease 만료 후 정리를 검증하는 추가 테스트 3개도 통과했으며 위 표에 포함한다.
- 실제 운영 DDL 적용·실제 타이머·외부 FCM·운영 다중 인스턴스/부하 검증은 수행하지 않았다. 최신 보완 커밋의 CI와 운영 활성화는 별도 확인 대상이다.

### 미시도 이월 검증 범위

2026-10-26 KST 로컬 대상 테스트 재실행 결과를 XML로 확인했다. NotificationSlotPostgresTest는 이월 정책에 맞춰 기존 1개를 변경하고 12개를 추가해 총 35개다.

| 테스트 | 건수 | 결과 |
| --- | --- | --- |
| NotificationSlotPostgresTest | 35 | skip/failure/error 0 |
| NotificationSlotWorkerTest | 6 | skip/failure/error 0 |
| PushSlotPolicyTest | 6 | skip/failure/error 0 |
| **합계** | **47** | **skip/failure/error 0** |

동일 묶음/다기기·다음 슬롯 선점·날짜 경계·늦은 복구·반복 이월·만료/대표 교체·동의 철회·기기 제외·시도 이력 보존·구 token 무효화·동시 생성/이월·예약 충돌·트랜잭션 롤백을 검증했다.

이월 변경 후 notification-service의 `test bootJar --rerun-tasks`도 성공했다. 전체 테스트 XML에서 skipped/failures/errors 중 1 이상인 항목이 없음을 확인했다. backend는 이번 변경 대상이 아니며 재실행하지 않았다. CI·실제 타이머·외부 FCM·운영 활성화는 별도 확인 대상이다.

팀 정책 최종 합의·기존 대기 Delivery 전환·실제 알림함 경로 확인과 성능/실패 관측이 필요하다.
초기 데이터 기준선·WAITING_POLICY 재개 등 10단계 잔여는 별도 후속으로 유지한다.
