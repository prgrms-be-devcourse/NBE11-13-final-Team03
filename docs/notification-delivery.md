# Delivery와 FCM — 8단계

## 범위와 현재 상태

8단계의 탈퇴 연동은 [notification-withdrawal.md](notification-withdrawal.md), 사용자 API는
[notification-user-api.md](notification-user-api.md)를 따른다. 이 문서는 Delivery/FCM 구현 계약이다.
코드·테스트 작성과 실행 성공·실제 푸시 수신·운영 활성화는 구분한다.
새 구현은 검증 전이며 실제 Firebase 자격증명/로컬 DB/전달 플래그를 변경하지 않았다.

서버에서만 Firebase Admin Java SDK 9.11.0을 사용한다.
프로젝트의 기존 Endpoint 계약은 FCM registration token이며, 설치 ID(FID) 계약으로 임의 전환하지 않는다.
SDK의 setToken은 이 버전에서 deprecated지만 지원되며, 프론트 계약 변경은 별도 검토한다.

## 수신 트랜잭션

요청 채널 ∩ 현재 수신 설정을 적용한다. 인앱 기본 true, 푸시 기본 false다.
푸시는 요청에 PUSH가 있고 동의가 있으며 활성 Endpoint가 있는 경우에만 Delivery를 만든다.
Notification·Endpoint별 Delivery·ConsumedMessage는 같은 수신 트랜잭션에서 저장된다.
하나라도 저장에 실패하면 전부 롤백하며 FCM은 이 안에서 호출하지 않는다.

- Delivery UNIQUE(notification_id, endpoint_id), next_retry_at NOT NULL.
- 여러 활성 Endpoint에는 각각 한 건을 생성한다. 같은 messageId 재수신이나 같은 사용자·이벤트의 새 메시지로 추가 생성하지 않는다.
- 나중에 새로 등록한 Endpoint나 나중에 켠 동의에 대해 과거 알림을 소급 발송하지 않는다.
- 인앱 허용이면 in_app_visible=true, 푸시만 허용이면 발송 부모 Notification은 in_app_visible=false다.
- 숨겨진 푸시 전용 행은 인앱 목록·미읽음 수·읽음·클릭·전체 읽음에서 제외한다.
- 기존 인앱 행은 DDL DEFAULT true로 그대로 노출한다.
- 양쪽 채널 모두 불가하면 기존 CHANNEL_DISABLED 영수증만 남긴다.
- PUSH-only + 활성 Endpoint 없음도 CHANNEL_DISABLED이며, 별도의 과거 기기 등록 대기는 만들지 않는다.

## 상태·점유·재검증

PENDING → SENDING(leaseToken) → SENT / PENDING(Backoff) / FAILED / SKIPPED.

1. 짧은 REQUIRES_NEW 트랜잭션에서 FOR UPDATE SKIP LOCKED로 한 건씩 점유한다.
2. 별도 짧은 REQUIRES_NEW 트랜잭션에서 사용자 advisory lock → Delivery 행 잠금 순서로 재검증한다.
3. Tombstone·만료·푸시 동의·활성 Endpoint를 다시 확인한다. 불가하면 SKIPPED다.
4. 트랜잭션을 끝낸 뒤 외부 FCM을 호출한다.
5. 짧은 트랜잭션에서 같은 leaseToken을 보유한 SENDING만 완료/실패 갱신한다.

Worker는 전용 단일 스케줄러(기본 5초 간격)로 실행하며 일반 스케줄러를 대체하지 않는다.
호출자 트랜잭션은 NOT_SUPPORTED로 중단해 FCM 동안 DB 트랜잭션·사용자 잠금을 잡지 않는다.
오래된 SENDING은 최대 batch-size만 복구하며 예전 작업자의 성공/실패/무효 토큰 처리가 새 점유를 덮지 못한다.

기본값: batch-size=10, max-attempts=5, lease-timeout=2분, retry-delay=1분, send-timeout=10초.
실패 Backoff는 retry-delay × 2^기존 retryCount(지수 상한 6)다.
이 값은 변경 가능한 초기 구현값이며 운영 정책 확정값이 아니다.

## FCM 결과·실패

- SENT는 FCM acceptance ID를 받았다는 뜻이다. 브라우저 표시/사용자 읽음 성공이 아니다.
- UNREGISTERED는 SKIPPED + 해당 Endpoint 비활성화다. 오래된 lease는 Endpoint도 변경하지 못한다.
- INVALID_ARGUMENT는 본문 오류일 수도 있으므로 FAILED로 처리하고 토큰을 임의 비활성화하지 않는다.
- SENDER_ID_MISMATCH·인증/권한/서드파티 인증 오류는 설정 오류다. 시도 횟수를 쓰지 않고 PENDING으로 돌린 뒤 현재 배치를 중단한다.
- QUOTA_EXCEEDED·UNAVAILABLE·INTERNAL 등 일시 오류/타임아웃은 Backoff 후 재시도한다.
- 만료·동의 철회·비활성 Endpoint·탈퇴는 발송하지 않는다. 재시도 한도 초과는 FAILED다.
- 오류 본문·토큰·알림 본문은 애플리케이션 로그에 남기지 않는다. 안전한 고정 오류 코드와 deliveryId/correlationId만 기록한다.

SDK connect/read/write timeout은 1/5/5초, Worker가 기다리는 SDK future는 기본 10초다.
타임아웃 시 future 취소를 요청하되 이미 전달된 외부 요청의 취소/회수를 보장하지 않는다.
SDK 내부에도 HTTP 재시도가 있을 수 있다. Delivery 시도 횟수와 실제 네트워크 요청 횟수는 같은 개념이 아니다.
FCM 성공 뒤 DB 기록 전에 장애가 나면 lease 복구 후 재전송될 수 있다. 정확히 한 번 푸시는 보장하지 않는다.
Webpush tag(notification-ID)는 브라우저의 같은 알림 갱신에 도움을 줄 뿐 중복 전달 방지 보장은 아니다.
푸시 TTL은 남은 expiresAt(최대 4주)에 맞추고, 클릭 링크는 설정된 공개 HTTPS origin의 내부 경로로 만든다.

## 탈퇴와 정리

탈퇴는 같은 사용자 잠금 아래 알림·Endpoint·설정을 삭제한다.
대기/SENDING을 포함한 Delivery는 알림·Endpoint 삭제 시 Notification 내부 두 FK의 ON DELETE CASCADE로 함께 삭제한다.
최종 데이터 삭제를 선택한 것이므로 탈퇴 사용자의 Delivery 발송 감사 행도 보존하지 않는다.
Tombstone과 소비 기록은 남고, 정리 후 지연 메시지/토큰/설정 재생성을 막는다.

발송 직전 재검증과 실제 외부 요청 사이에 탈퇴/철회가 발생할 수 있다. 이미 준비/진행 중인 호출의 즉시 취소까지 보장하지 않는다.
완료 갱신은 CAS이므로 삭제된 Delivery가 되살아나지 않는다. 이는 기존 최종 일관성 설계의 한계다.

## DB 적용 순서

Notification을 중지하고 대상 DB·계정을 확인한다. 기존에 적용된 SQL은 재실행하지 않는다.

1. notification-inbox.sql
2. notification-user-api.sql
3. notification-withdrawal.sql (8단계 Tombstone)
4. notification-delivery.sql (8단계 Delivery·in_app_visible)
5. 기존 delivery 스키마에 push_expires_at이 없으면 notification-push-expiry.sql 추가 적용(서버 중지·백업·대상 확인 후)
6. 새 Notification 기동(validate) → 새 Application 적용

빈 로컬 볼륨 초기화에도 이 순서를 사용한다. 기존 볼륨에는 신규 SQL을 수동 적용해야 한다.
기존 Notification 버전으로 롤백하면 푸시 전용 숨김 행/새 receipt의 호환성을 검토해야 한다.
서버를 끄고 데이터 백업·대상 확인 없이 스키마를 되돌리거나 신규 테이블을 삭제하지 않는다.

푸시 전용 만료·11단계 잠정 슬롯 정책의 최신 범위와 배포 호환성은 [푸시 슬롯 정책](notification-push-slots.md)을 따른다.

## 활성화 전제

기본 `NOTIFICATION_PUSH_ENABLED=false`. 비활성 시 FirebaseApp·PushSender·Worker·전용 스케줄러는 생성되지 않으며
자격증명 조회나 Firebase 호출도 하지 않는다. Delivery는 DB에 PENDING으로 저장될 수 있지만 발송하지 않는다.

활성 시에만 아래 설정을 사용한다.

- NOTIFICATION_PUSH_ENABLED=true
- FCM_PROJECT_ID: Firebase 프로젝트 ID
- NOTIFICATION_PUBLIC_BASE_URL: 공개 프론트 HTTPS origin
- GOOGLE_APPLICATION_CREDENTIALS: 저장소 밖의 서비스 계정 파일 경로 또는 운영 ADC 자격증명 방식

실제 키 파일은 저장소/이미지에 넣지 않고 운영의 별도 비밀값 마운트/자격증명 관리로 주입한다.
실제 브라우저 수신에는 프론트의 권한 요청·Service Worker·Firebase Web SDK·VAPID·토큰 등록이 추가로 필요하다.
이번 서버 테스트는 모의 PushSender/SDK를 사용하며 실제 Firebase/브라우저 수신을 검증했다고 표시하지 않는다.

Publisher/Receiver도 false를 유지한다. Tombstone·Delivery 추가만으로 활성화 조건이 모두 충족된 것은 아니다.
기존 필수 리뷰 보강(#38 지문 계약 분리 등), 운영 배포·자격증명·실제 수신 검증을 별도로 완료한다.
푸시 슬롯·하루 횟수·묶음/우선순위·pushExpiresAt 초안은 임의로 구현하지 않는다.

## 작성한 테스트

- 설정 비활성 시 SDK/Worker 없음, 활성 시 필수 설정 검사, 모의 Sender 대체
- SDK 모의 성공/만료/타임아웃/오류 분류·민감값 미출력
- PostgreSQL 수동 DDL+validate, 기기별 UNIQUE·수신 원자성·PUSH-only 비노출
- 커밋 뒤 전송 및 트랜잭션 밖 호출, 동의/Endpoint/만료 재검증
- 재시도/Backoff/한도, 설정 오류, 무효 토큰 처리
- 중단 복구와 이전 lease의 완료/Endpoint 변경 차단
- 탈퇴 후 대기·점유 Delivery 정리, 동시 점유 중복 차단

테스트는 작성 상태이며 실행 결과는 별도로 확인한다.

## 공식 참고

- [Firebase Admin Java 릴리스](https://firebase.google.com/support/release-notes/admin/java)
- [Admin SDK 설정](https://firebase.google.com/docs/admin/setup)
- [FCM 오류 코드](https://firebase.google.com/docs/cloud-messaging/error-codes)
