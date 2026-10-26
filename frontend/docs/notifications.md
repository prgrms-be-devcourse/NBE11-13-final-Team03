# 알림 목록·읽음·설정

## 확인한 계약

로컬 [UserNotificationFacadeController](../../backend/src/main/java/com/team3/backend/user/controller/UserNotificationFacadeController.java), [NotificationUserRelay](../../backend/src/main/java/com/team3/backend/client/NotificationUserRelay.java), notification-service의 [DTO](../../notification-service/src/main/java/com/team3/notification/api/NotificationUserDtos.java)·[Service](../../notification-service/src/main/java/com/team3/notification/service/NotificationUserService.java)·[유형](../../notification-service/src/main/java/com/team3/notification/message/NotificationType.java)을 기준으로 한다. 프런트엔드는 notification-service를 직접 호출하지 않고 기존 `/api` 프록시와 apiClient를 사용한다.

| API | 성공 응답 |
| --- | --- |
| GET `/api/notifications?beforeId=&size=20` | 200 `{ items, nextCursor, hasNext }` |
| GET `/api/notifications/unread-count` | 200 `{ available, unreadCount }` |
| PATCH `/api/notifications/{id}/read` | 204, 본문 없음 |
| PATCH `/api/notifications/read-all` | 204, 본문 없음 |
| POST `/api/notifications/{id}/click` | 204, 본문 없음 |
| GET·PUT `/api/users/me/notification-settings` | 200 `{ inAppEnabled, pushEnabled, pushConsentAt, updatedAt }` |

모든 요청은 `requiresAuth: true`다. 쿠키·CSRF·제한적 재발급은 기존 클라이언트를 재사용한다. PUT은 두 boolean을 모두 전달한다. 목록의 시각은 Instant 문자열이며 readAt·clickedAt은 null일 수 있다. 기본 설정은 인앱 켜짐·푸시 꺼짐이며 설정 시각과 동의 시각은 null일 수 있다.

목록은 ID 내림차순이며 첫 조회에서 beforeId를 생략한다. 다음 요청은 서버 nextCursor를 그대로 사용한다. 페이지 크기는 기본 20, 허용 범위 1~100이다. 응답의 ID 중복·역순·진행하지 않는 커서·요청 커서 밖 항목은 오류로 처리한다. 더 보기는 기존 요청을 중복 취소·재시작하지 않는다. 서버 page 기반 페이지네이션과 혼용하지 않는다.

Relay는 장애 시 목록·설정을 503 `NOTIFICATION_UNAVAILABLE`로, 미읽음 수를 200 `{ available: false, unreadCount: null }`로 반환한다. 존재하지 않거나 소유하지 않은 알림은 404, 탈퇴 사용자는 410이다. 일부 Relay 오류 본문은 code만 제공하므로 UI는 공통 ApiError의 HTTP 상태로도 안전하게 분기한다. 503은 빈 목록이 아니고 available=false는 0건이 아니다.

## 화면·클릭 정책

`/notifications`는 유형·본문·시각·읽음 상태와 더 보기·개별 읽음·전체 읽음을 제공한다. 로딩·빈 결과·오류·인증 필요를 구분한다. 전체 읽음은 불러온 페이지의 읽음 여부만으로 막지 않는다. 이전 페이지에 안 읽은 알림이 있을 수 있기 때문이다.

공고 보기는 개별 읽음 204 → 클릭 기록 204 → 관련 Query 갱신 → 상세 이동 순서다. 서버의 두 기록은 독립적이며 읽음 실패 시 클릭하지 않는다. 클릭 실패 시 이동하지 않으며, 이미 반영된 읽음·미읽음 수는 재조회한다. 갱신까지 실패한 경우에도 이동을 완료하지 않는다. 재시도 시 두 API는 서버의 멱등 처리를 따른다. 별도의 행동 이벤트는 보내지 않는다.

상세 경로는 검증한 opportunityId로 구성한다. ID를 사용할 수 없을 때의 link는 정규화된 `/opportunities/{양의 안전한 정수}`만 허용한다. 외부 주소·위험한 스킴·인증 경로·인코딩된 경로·쿼리 문자열은 대체 이동 경로로 허용하지 않는다. 페이지 이탈·계정 전환 이후 도착한 결과는 이동하지 않는다.

헤더 미읽음 배지는 인증 확인 후에만 조회한다. 99건 초과는 99+로 표시하고 접근 가능한 이름에는 전체 건수를 제공한다. 확인 중은 …, 조회 실패·이용 불가는 !와 접근 가능한 설명으로 구분한다. 폴링은 하지 않으며 목록의 새로고침으로 목록·미읽음 수를 다시 확인할 수 있다.

## 캐시·동시성

Query key는 `['notifications', userId, 'list' | 'unread' | 'settings']`다. 목록은 Infinite Query로 관리한다. 비활성 캐시는 gcTime=0이며 인증 전환 시 진행 중 요청을 취소하고 알림 캐시를 제거한다. 인증 확인 중·실패·비로그인에는 캐시가 있어도 개인 알림을 렌더링하지 않는다.

읽음·전체 읽음·클릭은 현재 계정의 목록·미읽음 수만, 설정 저장은 현재 계정의 설정만 취소·무효화·재조회한다. 낙관적 업데이트와 전체 캐시 초기화는 하지 않는다. 같은 작업은 진행 중 Promise를 공유하고 서로 다른 변경은 완료까지 차단한다. UI도 동일 계정의 변경 중 중복 제출을 막는다. 계정 소유자·인증 전환을 전후로 검사해 늦은 결과가 다음 계정을 갱신하지 않게 한다.

실패·응답 유실에도 서버 반영 가능성이 있어 최신 상태를 재조회하며 실패를 성공으로 표시하지 않는다. 재조회가 다시 실패해도 원래 변경 요청의 오류 상태는 보존한다. 여러 탭·기기의 동시 변경을 즉시 동기화하는 기능은 없다.

## 푸시 설정과 후속 작업

`/settings/notifications`에서 서버 인앱 설정을 수정한다. 푸시 설정·동의 시각은 조회한 서버 값으로 표시하되, 푸시 토글은 비활성 상태다. 인앱 저장 시 기존 pushEnabled를 그대로 보낸다. 이 화면에서 true를 새로 설정하거나 브라우저 구독 완료로 표시하지 않는다.

현재 Firebase SDK·브라우저 권한·FCM 토큰·서비스 워커·푸시 수신·엔드포인트 등록은 구현하지 않았다. 후속 단계에서 실제 브라우저 구독·해제, 서버 설정과 엔드포인트의 일치, 토큰 갱신·계정 전환을 함께 검증해야 한다.

## 검증 범위

Node 모의 API·QueryObserver·InfiniteQueryObserver와 실제 TSX 서버 렌더링 테스트로 DTO·커서·중복 조회, 정확한 204, 독립된 읽음/클릭 순서와 부분 실패, 미읽음 갱신, 설정 PUT·푸시 값 보존, 인증 캐시 격리, 안전한 이동 대상·오류 표시를 검증한다. 기존 헤더 레이아웃 fixture에도 미읽음 배지를 포함한다. 모의 테스트는 실제 인증·notification-service 통합을 대신하지 않는다.

`npm run lint`, `npm run build`(strict 타입 검사 포함)가 통과했다. `npm test`는 기존 169개와 신규 알림 20개를 합한 189개가 통과했으며 실패·생략은 없다. 기존 Chrome 헤더 fixture에서 배지 포함 PC·모바일 배치를 확인했지만 새 알림 화면의 실제 브라우저 조작을 검증한 것은 아니다.

실제 인증 없는 목록·미읽음 수·설정 GET은 백엔드 localhost:8080에서 모두 401 `AUTH_006`이었다. 이는 backend의 인증 제한 확인이며 notification-service의 가용성이나 인증 후 응답을 검증한 결과는 아니다. 허용된 로그인 테스트 세션이 없어 실제 목록·설정 조회, 읽음·클릭·설정 변경과 DB 반영은 BLOCKED다. 서버 데이터 변경 요청은 보내지 않았다.

후속 수동 검증은 허용된 계정으로 로그인한 뒤 커서 더 보기, 개별/전체 읽음의 204와 배지 갱신, 클릭의 별도 204 후 상세 이동, 서비스 장애 시 503·available=false, 인앱 설정 저장과 기존 푸시 값 보존을 확인한다. 실제 모바일 레이아웃·키보드 조작·페이지 이탈 후 이동 차단도 확인해야 한다.
