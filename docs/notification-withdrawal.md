# 탈퇴 Outbox와 Tombstone — 8단계 첫 검증 단위

## 범위와 상태

7단계(PR #62) 이후, 탈퇴와 지연 알림의 정합성을 먼저 구현한다.
이 문서는 구현 계약이며 테스트 실행 성공·병합·운영 활성화를 뜻하지 않는다.
이후 이어 붙인 Delivery·FCM과 추가 DDL은 [notification-delivery.md](notification-delivery.md)를 따른다.
Delivery를 포함한 변경 이후에는 관련 테스트를 다시 검증한다.
SQS·DLQ·푸시 슬롯·빈도 제한은 이번 범위가 아니다.

로그아웃(세션 종료)의 Endpoint 비활성화는 같은 Outbox·수신 경로를 쓰는 별도 제어 메시지 USER_SESSION_ENDED로 처리한다
([notification-session-end.md](notification-session-end.md)).

사용자 확정(2026-10-24): 탈퇴 시 기존 알림·Endpoint·설정을 삭제한다.
WithdrawnUser는 자동 삭제 없이 유지하며 운영 활성화 전 큐 보존·재발행 한도와 함께 보관 기간을 확정한다.
소비 기록은 지연·중복 메시지 처리 근거로 보존한다. 사용자 원본 정보·토큰·알림 본문은 Tombstone에 복사하지 않는다.

## Application 탈퇴 트랜잭션

`UserService.withdraw`의 기존 트랜잭션 안에서 다음을 수행한다.

1. 사용자 행을 PESSIMISTIC_WRITE로 잠근다. 없는 사용자는 기존 USER_NOT_FOUND다.
2. 해당 사용자·NOTIFICATION_REQUESTED·PENDING Outbox만 SKIPPED/USER_WITHDRAWN으로 변경한다.
3. USER_WITHDRAWN Outbox를 저장·flush한다.
4. 기존 Refresh Token·판정·프로필·사용자 삭제를 유지한다. 선호·관심은 기존 FK CASCADE로 정리한다.

위 동작은 모두 커밋되거나 모두 롤백된다. Notification HTTP/DB/FCM 호출은 탈퇴 트랜잭션 안에서 하지 않는다.
Outbox에는 User FK가 없어 사용자 삭제 뒤에도 탈퇴 메시지가 남는다.
USER_WITHDRAWN의 dedupKey는 `USER_WITHDRAWN:{userId}`이고, messageId는 재전송 동안 바뀌지 않는다.
PUBLISHING/PUBLISHED 메시지는 회수하지 않는다. 뒤늦게 도착하면 Notification의 Tombstone이 차단한다.

Fan-out은 사용자 ID 페이지를 찾은 뒤 사용자 행을 ID 오름차순으로 PESSIMISTIC_READ 잠금하고,
그 뒤 저장 상태를 잠그고 Outbox를 만든다. 탈퇴·선호 변경과 동일하게 User → Preference 잠금 순서를 유지한다.
Fan-out이 먼저 커밋하면 탈퇴가 그 Outbox를 취소하고, 탈퇴가 먼저 커밋하면 삭제된 사용자는 순회에서 빠진다.

## 메시지 계약·HTTP

공용 표본은 `contracts/user-withdrawn-v1.json`이다.

- schemaVersion=1, eventType=USER_WITHDRAWN
- messageId, sourceEventId, correlationId, causationId: UUID
- userId: 양의 정수, occurredAt: 탈퇴 시각
- title·opportunityId·channels·expiresAt을 요구하지 않는다. 탈퇴 제어 메시지는 만료되지 않는다.
- 미지원 버전·필수값 누락·문자열/소수 ID는 거부한다. 미래 선택 필드는 무시한다.

기존 전용 키 인증 `POST /internal/notification-messages`를 사용한다.
공통 Handler가 eventType에 따라 일반 알림/탈퇴 DTO로 분기한다. Notification 커밋 후 200 receipt를 반환한다.
탈퇴 receipt와 탈퇴 사용자에 대한 새 일반 알림 receipt는 `status=WITHDRAWN, notificationId=null`이다.
같은 messageId·같은 내용은 기존 소비 결과와 duplicateMessage=true,
같은 messageId·변경된 내용은 기존과 동일하게 409 MESSAGE_IDENTITY_CONFLICT다.

Publisher의 점유·오래된 PUBLISHING 복구는 두 eventType을 모두 처리한다.
USER_WITHDRAWN 발행 성공은 해당 messageId의 WITHDRAWN receipt를 확인했을 때만 인정한다.
일반 알림도 WITHDRAWN을 정상 억제 결과로 인정한다. HTTP 오류·재시도·lease 소유권 처리는 기존 경로를 재사용한다.

## Notification 트랜잭션과 동시성

Notification에는 Application User 행이 없고, 신규 사용자는 설정 행도 없다.
따라서 단순 exists 확인이나 존재하는 행 잠금만으로는 최초 토큰 등록과 탈퇴의 경합을 막을 수 없다.

일반 메시지 수신·탈퇴 수신·설정/Endpoint/읽음/클릭 변경은 동일 사용자별 PostgreSQL
transaction advisory lock을 사용한다(`hashtextextended('yon.notification.user:' + userId, 0)`).
잠금은 기존 DB 트랜잭션에 귀속되고 커밋/롤백 때 해제된다. 별도 잠금 테이블·JVM 락은 사용하지 않는다.
해시 충돌 시 무관한 사용자가 직렬화될 수 있지만 소유권 판단에는 원본 userId를 사용한다.

탈퇴 수신은 같은 트랜잭션에서 소비 중복 확인 → Tombstone 저장 → Endpoint/설정/알림 삭제 → 소비 기록 저장을 수행한다.
알림 삭제 시 기존 ConsumedMessage의 FK는 ON DELETE SET NULL이다. 기존 영수증은 남지만 알림이 다시 생성되지는 않는다.
삭제 또는 소비 기록 저장 실패 시 Tombstone과 모든 삭제가 함께 롤백된다.
같은 사용자의 여러 탈퇴 메시지도 최초 Tombstone 시각을 덮어쓰지 않는다.

일반 수신은 기존 소비 기록을 먼저 반환하고, 새 메시지에 대해서는 만료·설정보다 Tombstone을 먼저 확인한다.
탈퇴 수신이 먼저라면 알림을 만들지 않고 WITHDRAWN 소비 기록만 남긴다.
일반 수신이 먼저라면 해당 트랜잭션의 커밋 뒤 탈퇴가 그 알림을 삭제한다.
설정/Endpoint 등록도 같은 잠금 안에서 Tombstone을 재확인하므로 탈퇴 정리 뒤 행이 다시 생기지 않는다.

모든 사용자 API는 Tombstone이 있으면 410 USER_WITHDRAWN이다.
backend는 호출별 허용 오류에 이 410을 추가해 그대로 전달한다. 미읽음 장애 대체값(available=false)과 구분한다.
읽기 전용 조회는 Tombstone을 검사하되 변경용 advisory lock은 잡지 않는다.
탈퇴 커밋 전 이미 진행 중인 조회의 응답까지 취소하는 것은 보장하지 않는다.

## 최종 일관성과 기존 인증

Application 탈퇴 성공 응답은 사용자 삭제·탈퇴 Outbox 커밋을 뜻한다. Notification 정리 완료 응답을 기다리는 동기 Saga가 아니다.
탈퇴 메시지가 도착하기 전까지, 또는 향후 이미 진행 중인 FCM 호출까지 즉시 취소하는 것은 보장하지 않는다.
기존 Access Token의 전 서비스 즉시 폐기 정책도 이 작업에서 변경하지 않는다.
Tombstone 처리 후에는 남아 있는 Access Token으로 Notification 데이터를 다시 생성/조회할 수 없다.
같은 카카오 계정의 재가입은 새 사용자 ID로 구분한다. 탈퇴한 ID를 재사용하거나 DB 시퀀스를 임의로 되돌리지 않는다.

## DB 적용·배포 순서

탈퇴 부분의 새 테이블은 Notification 소유 `withdrawn_users(user_id, withdrawn_at, received_at)` 하나다.
Delivery 연결 버전에서는 notification-delivery.sql도 뒤이어 적용해야 한다.
기존 Application Outbox와 Notification 소비 테이블은 재사용한다.

1. 서버를 중지하고 DB·계정을 확인한다.
2. 기존 inbox/user-api DDL 적용을 확인한다(이미 적용된 SQL은 재실행하지 않는다).
3. `notification_user`로 `database/scripts/notification-withdrawal.sql`을 한 번 적용한다.
4. 새 Notification 버전을 기동해 validate한다.
5. 새 Application 버전을 적용한다.

빈 로컬 볼륨의 DDL 순서는 inbox → user-api → withdrawal이다. 기존 볼륨에는 수동 적용이 필요하다.
구 Notification은 USER_WITHDRAWN을 지원하지 않아 400으로 거부하므로 새 Application을 먼저 활성화하지 않는다.
롤백 시 이미 쌓인 제어 메시지와 Tombstone/410/receipt 호환성을 함께 검토한다.

`NOTIFICATION_PUBLISHER_ENABLED=false`, `NOTIFICATION_RECEIVER_ENABLED=false`를 유지한다.
실제 .env·DB·FCM 자격증명은 이 구현에서 변경하지 않는다.
Tombstone 추가만으로 발송 활성화 조건이 모두 충족된 것은 아니다(Delivery/FCM 및 기존 필수 리뷰 후속 검증 필요).

## 작성한 검증

- Application 탈퇴와 미발행 취소·제어 Outbox의 원자성, Outbox 실패/사용자 삭제 실패 롤백
- 동시 탈퇴의 단일 제어 Outbox, Fan-out 사용자 잠금과 취소 정합성
- 제어 메시지 계약, Publisher의 전송·WITHDRAWN receipt 확인·lease 복구
- Notification의 알림·설정·Endpoint 삭제와 소비 영수증 보존
- 중복·역순·오래된 탈퇴 메시지, 지연 알림 억제, identity 충돌
- 소비 기록 실패 시 모든 정리 롤백
- 탈퇴와 메시지 수신·Endpoint 등록·설정 변경의 동시 실행
- 실제 HTTP 탈퇴 수신과 410 사용자 API, backend의 410 전달

실행 결과는 별도로 확인한다. 본 문서는 테스트 성공이나 8단계 전체 완료를 주장하지 않는다.
