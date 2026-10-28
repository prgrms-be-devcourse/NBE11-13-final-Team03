# 로그아웃 세션 종료와 푸시 Endpoint 정리

## 목적

로그아웃·계정 전환 때 프런트가 브라우저 푸시 구독을 해제하고 `DELETE /api/users/me/notification-endpoints/{id}`를 보내지만,
그 요청이 실패하면 로그아웃 뒤에는 인증 쿠키가 없어 다시 보낼 수 없고 이전 계정의 토큰이 서버에 남는다.
그래서 로그아웃 자체가 서버에서 Endpoint 정리를 보장하도록, 탈퇴(`USER_WITHDRAWN`)와 같은 Outbox 경로로
세션 종료 제어 메시지 `USER_SESSION_ENDED`를 보낸다. 프런트 변경은 없다.

푸시 Endpoint는 사용자가 아니라 **로그인 세션(기기)** 의 자원이다. 한 기기의 로그아웃이 다른 기기의 알림을 끊지 않도록
Endpoint에 등록한 세션을 기록하고, 로그아웃은 그 세션의 Endpoint만 비활성화한다.

## Endpoint 등록 (세션 바인딩)

- backend facade는 브라우저 본문 `{fcmToken}`에 인증된 `CustomUserDetails.sessionId`를 붙여
  내부 요청 `{fcmToken, sessionId}`로 보낸다. 브라우저가 보낸 sessionId는 받지 않는다.
- Notification은 `notification_endpoints.session_id`(varchar(36), NULL 허용)에 저장한다.
  같은 토큰 재등록은 현재 세션으로 옮기고, sessionId가 없는 요청(구 Application)은 기존 값을 유지한다.
- 세션 ID는 기기 쿠키별로 재로그인 때 재사용된다(`TokenService`). 로그인 → 로그아웃 → 로그인은 같은 세션 ID일 수 있다.

## 로그아웃 트랜잭션 (Application)

`AuthService.logout`의 기존 트랜잭션 안에서 Refresh Token 삭제와 `USER_SESSION_ENDED` Outbox 저장을 함께 커밋한다.
Notification HTTP/DB 호출은 하지 않는다. 로그아웃 응답 204는 Outbox 커밋을 뜻하고, 정리 완료를 기다리지 않는다.

- dedupKey는 `USER_SESSION_ENDED:{messageId}`다. 세션 ID가 재사용되므로 사용자·세션 조합으로 중복 제거하지 않는다.
- `logoutAll`은 세션 ID가 없어 이 메시지를 보내지 않는다. 현재 호출처가 없다.
- 탈퇴는 기존대로 `USER_WITHDRAWN`이 Endpoint 전체를 삭제한다.

## 메시지 계약

공용 표본은 `contracts/user-session-ended-v1.json`이다.

- schemaVersion=1, eventType=USER_SESSION_ENDED
- messageId, sourceEventId, correlationId, causationId: UUID
- userId: 양의 정수, sessionId: 공백·제어문자 없는 ASCII 최대 36자, occurredAt: 로그아웃 시각
- title·opportunityId·channels·expiresAt을 요구하지 않는다. 만료되지 않는다.

Publisher(HTTP·SQS)는 세 eventType(NOTIFICATION_REQUESTED, USER_WITHDRAWN, USER_SESSION_ENDED)을 발행한다.
HTTP 모드는 해당 messageId의 `status=SESSION_ENDED` receipt를 확인했을 때만 발행 성공으로 본다.
일반 알림·탈퇴 메시지에 SESSION_ENDED receipt가 오면 발행 실패다.

## Notification 처리

`NotificationMessageHandler`가 eventType으로 분기해 `UserSessionEndHandler` → `NotificationSessionEndTransaction`(REQUIRES_NEW)로 보낸다.

1. 사용자별 advisory lock(등록·탈퇴와 같은 잠금)
2. 같은 messageId의 소비 기록이 있으면 기존 결과와 duplicateMessage=true. 내용이 다르면 409 MESSAGE_IDENTITY_CONFLICT
3. `UPDATE notification_endpoints SET active=false WHERE user_id=? AND session_id=? AND active`
4. `consumed_messages`에 `result=SESSION_ENDED` 기록 (notification_id 없음)

- 같은 사용자의 다른 세션, session_id가 NULL인 기존 행, 같은 세션 ID 문자열의 다른 사용자는 건드리지 않는다.
- 탈퇴 뒤 도착한 세션 종료는 바꿀 행이 없어도 소비 기록만 남긴다. Tombstone 410을 내지 않는다.
- 설정(push_enabled)은 바꾸지 않는다. 로그아웃은 동의 철회가 아니다.
- 비활성 행은 다음 로그인의 재등록으로 다시 활성화되고 그 세션에 묶인다.
- 소비 기록 저장 실패 시 비활성화도 함께 롤백된다.

## DB 적용·배포 순서

새 컬럼·인덱스는 `database/scripts/notification-session-end.sql`(`notification_user`)이다. Notification은 `ddl-auto: validate`라
컬럼이 없으면 기동하지 않는다. 기존 행은 session_id NULL로 남고 로그아웃의 영향을 받지 않는다.

1. Notification을 멈추고 `notification-session-end.sql`을 한 번 적용한다 (빈 DB는 inbox → user-api → session-end → withdrawal → delivery 순서).
2. 새 Notification을 기동해 validate한다.
3. 새 Application을 배포한다. 구 Notification은 USER_SESSION_ENDED를 400으로 거부하므로 Application을 먼저 올리지 않는다.
   SQS 모드에서는 거부된 메시지가 DLQ로 가므로 순서를 지킨다.

## 검증

- backend: 로그아웃이 세션 삭제와 Outbox 저장을 함께 수행하는 단위 테스트, 공용 계약 JSON 일치·dedupKey,
  HTTP Publisher의 SESSION_ENDED receipt 요구, SQS Publisher의 세 eventType 발행, facade의 세션 전달
- notification-service: 계약 디코딩·거부, Handler 검증, Postgres에서 세션 단위 비활성화·멱등·identity 충돌·탈퇴 이후·롤백,
  LocalStack SQS 소비, 내부 API의 sessionId 전달·길이 검증

실행 결과는 PR 본문에 적는다. 배포 환경의 실제 FCM 미수신 확인은 별도 검증 대상이다.
