# 알림 사용자 API — 7단계

> 8단계 탈퇴 연동의 Tombstone·410 계약과 추가 DDL은 [notification-withdrawal.md](notification-withdrawal.md)를 따른다.
> 이 문서의 "Tombstone 미구현" 등 제외 범위는 7단계 시점의 이력이다.
> 8단계 푸시 전용 알림의 인앱 비노출과 FCM/Delivery 설정은 [notification-delivery.md](notification-delivery.md)를 따른다.

## 경계

사용자 → ALB/nginx → backend(쿠키 JWT 인증·기존 CSRF) → OpenFeign → notification-service.
사용자 인증은 backend, 알림 조회·변경 및 소유권 검사는 Notification이 맡는다.
backend에는 알림 Entity/Repository 또는 `com.team3.backend.notification` 패키지를 만들지 않는다.
`user/controller/UserNotificationFacadeController`는 인증된 `CustomUserDetails.userId`만 전달한다.
브라우저가 보낸 userId·내부 키·사용자 헤더는 전달하지 않는다.
Notification은 Application DB를 조회하지 않으며 사용자 ID는 스칼라다.

내부 URL은 `/internal/users/{userId}` 아래다. 공개 URL을 Notification에 직접 라우팅하지 않는다.
`X-INTERNAL-KEY`는 기존 전용 `NOTIFICATION_INTERNAL_API_KEY`를 사용한다.
`NOTIFICATION_USER_API_ENABLED=true`일 때만 사용자 내부 경로를 열고 키 누락·오류·중복은 401이다.
브라우저 쿠키/JWT만으로 내부 API를 호출할 수 없다. 서비스 키 보유자는 신뢰된 Application이므로
사용자별 권한 분리는 Application에서 추출한 userId와 Notification의 소유권 검사에 의존한다.
포트·보안 그룹과 프록시 설정으로 `/internal/**`에 대한 외부 접근도 차단해야 한다.

## HTTP 계약

모든 외부 경로는 인증이 필요하며 상태 변경에는 backend의 기존 CSRF 계약을 적용한다.
내부 경로는 `/internal/users/{userId}`에 아래 "내부 suffix"를 붙인다.

| Method | 외부 URL | 내부 suffix | 성공 |
|---|---|---|---|
| GET | `/api/notifications` | `/notifications` | 200 |
| GET | `/api/notifications/unread-count` | `/notifications/unread-count` | 200 |
| PATCH | `/api/notifications/{id}/read` | `/notifications/{id}/read` | 204 |
| PATCH | `/api/notifications/read-all` | `/notifications/read-all` | 204 |
| POST | `/api/notifications/{id}/click` | `/notifications/{id}/click` | 204 |
| GET | `/api/users/me/notification-settings` | `/notification-settings` | 200 |
| PUT | `/api/users/me/notification-settings` | `/notification-settings` | 200 |
| POST | `/api/users/me/notification-endpoints` | `/notification-endpoints` | 200 |
| DELETE | `/api/users/me/notification-endpoints/{id}` | `/notification-endpoints/{id}` | 204 |

### 목록·읽음·클릭

- `size` 기본 20, 1~100. 선택 `beforeId`는 양의 정수다. 서버 알림 ID 내림차순으로 조회한다.
- 응답: `{items: [...], nextCursor: number|null, hasNext: boolean}`. 다음 요청의 beforeId에 nextCursor를 넣는다.
- 항목: id, opportunityId, type, title, content, link, createdAt, readAt, clickedAt.
- 페이지 번호/전체 개수·추가 필터는 제공하지 않는다. 커서 조회는 고정 스냅샷을 보장하지 않는다.
- 읽음·클릭은 최초 시각만 기록하고 반복 요청으로 시각을 덮어쓰지 않는다.
- 클릭은 읽음을 암묵적으로 변경하지 않는다. 화면에서 필요하면 읽음 API를 별도 호출한다.
- 전체 읽음은 해당 UPDATE가 보는 사용자 미읽음 행에 적용된다. 나중에 생성된 행까지 읽음으로 만들지 않는다.
- 다른 사용자 또는 없는 알림은 모두 404로 응답한다.
- 미읽음 수 정상 응답: `{available: true, unreadCount: 3}`.
- 만료는 새 메시지의 수신/발송 기준이며 이미 저장한 인앱 알림을 목록에서 숨기는 기준으로 사용하지 않는다.

### 수신 설정

PUT 본문은 `{inAppEnabled: boolean, pushEnabled: boolean}`이며 두 필드가 모두 필요하다.
GET/PUT 응답은 두 필드와 `pushConsentAt`, `updatedAt`이다.
저장 전 기본값은 인앱 true·푸시 false, 두 시각은 null이다. GET은 기본 행을 만들지 않는다.
pushEnabled=true는 해당 사용자의 명시적 동의 설정 요청이다. 서버 Clock으로 동의 시각을 기록하며
true→true는 최초 시각을 보존하고, false로 철회하면 null, 재동의하면 새 시각이다.
현재 동의 상태만 저장하며 별도의 동의 변경 이력/약관 버전 관리 기능은 포함하지 않는다.

메시지 수신 시 요청 IN_APP 채널과 저장된 inAppEnabled의 교집합을 적용한다.
인앱 거부는 알림 없이 CHANNEL_DISABLED 소비 기록으로 남기고, 설정을 바꿔도 같은 messageId를 재생성하지 않는다.
설정 변경으로 기존 알림을 소급 삭제하지 않는다.
PUSH 동의 저장만으로 FCM·Delivery 또는 메시지 전달 기능이 활성화되지 않는다.

### Endpoint

POST 본문 `{fcmToken: string}`. 토큰은 비어 있지 않은 공백·제어문자 없는 ASCII, 최대 2048자다.
응답은 `{id, active: true}`이며 토큰을 응답·예외·로그에 포함하지 않는다.
같은 사용자의 같은 토큰 등록은 동일 ID를 반환하고 재활성화한다. 등록 자체가 푸시 동의를 뜻하지 않는다.
전역 UNIQUE(fcm_token)와 PostgreSQL UPSERT로 동시 등록 중복을 막는다.
다른 사용자의 토큰은 소유권을 임의로 바꾸지 않고 409로 거부한다.
공용 브라우저의 계정 전환 시 토큰 이전/재발급 UX는 별도 합의가 필요하며 이번 단계에서 자동 이전하지 않는다.
DELETE는 본인 Endpoint를 비활성화하고 반복 요청도 204다. 다른 사용자/없는 ID는 404다.
비활성 토큰도 소유권을 유지하며 타 사용자 이전·주기적 정리는 후속 정책이다.

## 동기 호출·장애

- NotificationUserClient만 `feign-java11`의 JDK HttpClient 전송기를 사용한다(PATCH 지원).
  이름과 달리 프로젝트 Java 버전은 25 그대로다. HTTP/1.1로 호출한다.
- 해당 Feign child context에만 Client·인터셉터·옵션을 등록한다. 기존 수집/Outbox Client는 변경하지 않는다.
- 연결 1초·요청 옵션 2초, 리다이렉트 금지, Feign 자동 재시도 없음.
- 요청별 UUID X-Correlation-ID를 생성한다. 사용자 입력 내부 키/쿠키는 복사하지 않는다.
- Facade 동시 호출은 기본 8개(`yon.notification.facade.max-concurrent-requests`), 대기열 없이 초과 시 즉시 실패한다.
- 응답 본문은 최대 1MiB로 읽고 JSON 객체인지 확인한다. 미읽음 수는 boolean/비음수 long도 확인한다.
- 전송 오류·예상 밖 응답·원격 401/403(내부 키 설정 오류)·3xx·5xx는 503 NOTIFICATION_UNAVAILABLE.
  미읽음 수는 이 경우에만 200 `{available:false, unreadCount:null}`로 반환하며 0으로 위장하지 않는다.
- 계약 오류는 해당 호출이 낼 수 있는 것만 상태를 유지한다: 단건 읽음·클릭·Endpoint 비활성화의 404, Endpoint 등록의 409.
  원격 진단 본문은 중계하지 않고 고정 코드로 응답한다.
- 그 밖의 호출(목록·미읽음 수·전체 읽음·수신 설정)에는 정상적인 404가 없으므로, 원격 400·404·409도 라우팅·계약 불일치로 보고
  503 NOTIFICATION_UNAVAILABLE(미읽음 수는 `available:false`)로 처리한다. 요청 검증은 backend가 먼저 수행한다.
- backend의 사용자 인증 401·권한/CSRF 403은 기존 보안 필터에서 처리한다.

## 설정·DB 적용

사용자 API는 메시지 전달과 별개로 제어한다. 모든 신규 플래그의 기본값은 false다.

| 프로젝트 | 설정 | 의미 |
|---|---|---|
| backend | NOTIFICATION_FACADE_ENABLED | 사용자 요청의 내부 전달 허용 |
| backend | NOTIFICATION_BASE_URL | Notification HTTP origin(활성 시 필수) |
| 양쪽 | NOTIFICATION_INTERNAL_API_KEY | 일치하는 전용 서비스 키 |
| Notification | NOTIFICATION_USER_API_ENABLED | 사용자 내부 API 개방 |

`NOTIFICATION_PUBLISHER_ENABLED=false`, `NOTIFICATION_RECEIVER_ENABLED=false`는 유지한다.
사용자 API 개발 검증을 위해 두 전달 플래그를 켜지 않는다. 실제 `.env`는 변경하거나 커밋하지 않는다.

기존 DB는 서버를 중지하고 대상 DB 확인 후 `notification_user`로 다음 SQL을 한 번 적용한다.

1. 기존 `database/scripts/notification-inbox.sql` 적용 여부 확인(이미 있으면 재실행 금지).
2. 신규 `database/scripts/notification-user-api.sql` 적용.
3. 새 Notification 버전 기동(`ddl-auto=validate`).

새 SQL은 설정·Endpoint 두 테이블과 조회 인덱스를 추가하며 application/public에는 접근하지 않는다.
재적용·다른 계정 실행은 오류로 중단한다. 실패 시 데이터를 지우거나 create/update로 우회하지 않는다.
빈 로컬 Docker 볼륨은 `database/local/03-apply-notification-ddl.sh`가 inbox → user-api 순서로 적용한다.
기존 볼륨에는 초기화 스크립트가 다시 돌지 않으므로 수동 적용해야 한다.

## 검증 상태·제외 범위

테스트 작성: Notification 전용 키/입력/9개 라우트, PostgreSQL 실제 DDL+validate,
사용자별 목록·읽음·클릭·설정 교집합·Endpoint 멱등성/소유권/동시 등록,
backend 인증·CSRF·사용자 ID 위조 차단·장애 응답·동시 호출 제한,
실제 Feign PATCH/JSON/키/리다이렉트 금지/자동 재시도 없음/child context 격리.
작성과 실행 성공은 다르며 실행 결과는 별도로 확인한다.

이번 단계에 FCM·Delivery·탈퇴 Tombstone·SQS·푸시 묶음/빈도 제한·운영 프록시/배포 변경은 포함하지 않는다.
Tombstone 및 활성화 전 필수 후속 과제 검증 전에는 메시지 전달 플래그를 켜지 않는다.
