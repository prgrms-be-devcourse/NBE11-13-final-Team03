# Application 이벤트 Worker·사용자별 Outbox — 4단계

## 범위

날짜 이벤트를 점유하고 저장 사용자에게 보낼 완성된 메시지를 Application Outbox에 저장한다.
Notification 프로젝트·Security·기존 수집/정규화·판정 계산은 변경하지 않는다.
날짜 스캔과 이 Worker는 별도 설정으로 활성화한다.

대상 이벤트는 APPLICATION_OPENED, APPLICATION_CLOSED, DEADLINE_SOON뿐이다.
다른 이벤트 유형은 명시적으로 FAILED 처리하고 자동으로 날짜 알림으로 변환하지 않는다.
버전·신청 가능성 변화의 사용자 선별은 후속 단계다.

이번 단계는 Outbox PENDING 저장까지만 수행한다.
HTTP/Feign/SQS Publisher·Notification Handler·FCM·탈퇴 연동은 추가하지 않는다.
Outbox의 발행 상태·lease 필드는 목표 모델대로 준비하되 발행 상태 전이/재시도 코드는 후속 단계에서 추가한다.
이벤트 Worker의 재시도와 향후 Publisher의 재시도를 혼동하지 않는다.

## 점유·순회·트랜잭션

1. PENDING 처리 시각이 도래했거나 PROCESSING heartbeat가 만료된 이벤트 ID를 조회한다.
2. 조건부 UPDATE로 점유하고 새 UUID leaseToken을 부여한다.
3. 별도 배치 Bean의 트랜잭션에서 이벤트 행을 쓰기 잠금하고 status/token을 검증한다.
4. 현재 정책·신청기간을 확인하고 기존 저장 사용자 ID 키셋 조회를 재사용한다.
5. 저장 여부를 다시 조회·잠금한 뒤 사용자별 Outbox를 저장한다.
6. 마지막 userId와 heartbeat를 같은 트랜잭션에 저장한다.
7. 조회할 사용자가 없으면 같은 소유권 검사 아래 COMPLETED로 완료한다.

전체 사용자를 하나의 트랜잭션에 넣지 않는다. 숨김은 후보 필터가 아니며 저장+숨김도 포함한다.
저장 재확인과 현재 정책 읽기 잠금은 각 페이지 커밋까지 유지한다.
키셋 순회 중 새로 저장한 사용자는 포함되지 않을 수 있으며 소급 알림을 보장하지 않는다.

배치 오류는 해당 배치의 Outbox와 커서를 함께 롤백한다. 이전 배치 커밋은 유지된다.
오류 기록/재시도 예약은 실패한 배치 트랜잭션 밖의 별도 트랜잭션에서 한다.
재점유는 기존 커서를 유지하고 토큰만 교체한다.
예전 작업자는 페이지 저장·완료·재시도 예약을 수행할 수 없다.
재점유가 페이지 잠금을 기다린 경우 커밋된 heartbeat를 다시 평가한다.
정상 Worker는 다음 페이지에 들어갈 때도 토큰을 확인한다.

retryCount는 최초 점유를 포함한 실행 시도 횟수다.
상한 도달 후 오류나 만료된 PROCESSING은 FAILED로 종료한다.
FAILED 자동 재개나 운영자용 재처리 API는 추가하지 않는다.
기존 claim()/상태 변경 보조 메서드는 이전 테스트 호환을 위해 남겼으며,
실제 4단계 Worker는 토큰 기반 Coordinator/Batch 경로만 사용한다.

## 메시지·멱등성

- Outbox messageId와 dedupKey는 각각 UNIQUE다.
- dedupKey는 NOTIFICATION_REQUESTED:{sourceEventId}:{userId}다.
- 동일 이벤트/사용자 재처리는 기존 Outbox와 messageId를 보존한다.
- 배치가 무결성 오류를 만나면 성공으로 삼키지 않고 롤백/재시도한다.
- User/Opportunity FK를 두지 않아 사용자 삭제가 Outbox를 CASCADE 삭제하지 않는다.
- Message envelope는 schemaVersion=1과 messageId, eventType, sourceEventId, correlationId,
  causationId, userId, opportunityId, opportunityVersionId, type, channels, title, content,
  link, occurredAt, expiresAt, payload를 포함한다.
- sourceEventId/causationId는 이벤트 UUID, opportunityVersionId는 이벤트 생성 근거 버전이다.
  현재 정책으로 날짜 의미를 재검증하되 근거 버전을 몰래 바꾸지 않는다.
- 링크는 확정 메시지 예제의 /opportunities/{id}, 문구는 해당 예제 기반의 초기 표현을 사용한다.
- Notification은 추후 이 메시지만으로 처리하며 Application Entity/DB를 조회하지 않는다.

요청 채널은 시작·마감 임박 IN_APP/PUSH, 마감 IN_APP이다.
이는 푸시 동의 완료나 실제 발송을 의미하지 않는다.
최종 수신 설정·Endpoint·탈퇴 검증은 Notification 단계에서 수행한다.

## 신선도·만료

현재 YOUTH_POLICY 버전의 유효 신청기간으로 날짜 의미를 재확인한다.
마감 임박은 원래 임계일이 오늘이고 현재 기간도 같은 마감·임계값을 가지는 경우에만 새 요청을 만든다.
마감이 연장되거나 지나간 D-3 등을 뒤늦게 요청하지 않는다.
모집 시작은 현재 OPEN과 해당 시작일을, 모집 종료는 정책 전체 CLOSED와 해당 마감일을 확인한다.
원래 억제된 이벤트나 현재 조건 불일치/만료는 Outbox 없이 완료하고 fanoutSkipReason을 기록한다.

- DEADLINE_SOON: 해당 마감일 다음 날 KST 00:00을 배타적 만료 시각으로 사용
- APPLICATION_OPENED: 발생 후 3일과 해당 신청기간 마감 중 빠른 시각
- APPLICATION_CLOSED: 발생 후 3일

동일 시작일의 복수 열린 기간은 일부 기간 종료를 정책 전체 마감으로 간주하지 않는다.
이 경우 마지막 마감을 사용하며 종료일 미정 기간이 있으면 3일 만료를 사용한다.

Outbox 생성 직전 만료도 확인한다. 이미 만들어진 요청을 이번 Worker가 소급 수정하지 않는다.
저장 해제 시 기존 미발행 요청을 취소하는 정책은 미확정이므로 구현하지 않는다.
마감 연장에 따른 기존 PENDING 요청 취소와 탈퇴 Outbox/Tombstone은 해당 후속 단계에서 연결한다.
버전 변경·기준선·판정 상태 비교는 이번 선별 로직에 임의로 추가하지 않는다.

## 설정

자동 Worker는 기본 비활성화다. app.scheduling.enabled=false이면 자동 실행하지 않는다.

| 속성 | 기본값 | 의미 |
| --- | --- | --- |
| yon.tracking.fanout.enabled | false | 자동 실행 |
| yon.tracking.fanout.batch-size | 500 | 사용자 페이지 크기, 1~500 |
| yon.tracking.fanout.max-events-per-run | 10 | 폴링당 조회할 이벤트 수 |
| yon.tracking.fanout.lease-timeout | PT30M | 페이지 커밋 생존 시각 기준 재점유 시간 |
| yon.tracking.fanout.retry-delay | PT1M | 이벤트 배치 오류 후 재시도 대기 |
| yon.tracking.fanout.max-attempts | 5 | 최초 점유 포함 최대 실행 시도 횟수 |
| yon.tracking.fanout.poll-interval-ms | 60000 | 자동 폴링 간격 |

수치는 운영 측정 전 초기 설정값이며 확정 운영 정책으로 간주하지 않는다.
실패 로그에는 eventId/leaseToken/예외 종류를 남기고 메시지 본문이나 비밀값은 출력하지 않는다.

## DB 적용

기존 date-tracking.sql 적용 후 서버/Worker를 중지하고
database/scripts/event-fanout-outbox.sql을 application_user로 한 번 실행한다.

- 기존 이벤트에 lease_token, last_user_id(DEFAULT 0), fanout_skip_reason 추가
- Outbox 테이블·UNIQUE·조회 및 lease 복구 인덱스 추가
- 기존 이벤트·스캔·정책·회원 데이터는 삭제하지 않음

이 SQL은 재실행 시 실패하며 트랜잭션 전체를 롤백한다.
구조가 다른 기존 Outbox를 자동 합치거나 덮어쓰지 않는다.
배포 전 백업·잠금·중단 및 복구 절차를 검토한다.
자동 Worker를 꺼도 JPA는 새 컬럼/테이블을 검증하므로 서버 실행 전 DDL 적용이 필요하다.
운영/기존 로컬 DB에서 ddl-auto=create/update로 우회하지 않는다.
실제 DB 적용과 환경변수 변경은 코드 구현 작업에서 수행하지 않는다.

## 검증

Docker를 켜고 backend에서 테스트한다. 실제 로컬/운영 DB가 아닌 폐기 PostgreSQL 컨테이너를 사용한다.

```bash
./gradlew test --rerun-tasks +  --tests 'com.team3.backend.tracking.service.DateNotificationMessageFactoryTest' +  --tests 'com.team3.backend.tracking.service.EventFanout*'
```

검증 대상:

- 날짜 이벤트별 채널·만료·메시지 필수값
- 현재 정책·저장 상태 재확인, 숨김+저장 포함, 숨김만 있는 사용자 제외
- 배치 커밋 후 재점유/진행 위치 복구와 기존 messageId 유지
- 무결성 오류 시 전체 페이지/커서 롤백, 대기 후 재시도
- 토큰 교체 후 예전 Worker의 저장·재시도 차단
- 동시 점유의 단일 승자 및 잠금/heartbeat 재확인
- 재시도 상한, 억제/조건 변경/미지원 이벤트 처리
- User 삭제 시 Outbox 비연쇄 삭제
- 관리자 SQL 데이터 보존·두 UNIQUE·소유권·상대 계정 접근 차단

이후 DatabaseBoundaryPostgresTest, 전체 backend test/bootJar와 Notification 독립 test/bootJar를 회귀 확인한다.
로컬 자동 Worker 확인은 테스트 성공 후 별도로 한다.
저장 사용자가 없는 이벤트는 Outbox 없이 COMPLETED가 되는 것이 정상이다.
이번 단계에서는 Outbox가 PENDING인 채 남는 것이 정상이며 실제 발행 성공으로 보고하지 않는다.
