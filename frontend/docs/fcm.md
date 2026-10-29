# FCM 웹 푸시

## 핵심 구조

- `src/push/browser.ts`: Firebase App·Messaging 초기화, 지원 검사, 전용 서비스 워커 등록, 토큰 발급·해제와 포그라운드 수신을 담당한다. `?worker&url`로 Vite가 워커를 별도 번들로 생성한다. 워커 파일 하위의 `yon-fcm-push/` 전용 scope를 사용하며 앱 전체에 fetch 처리를 추가하지 않는다.
- `model.ts`·`storage.ts`·`lifecycle.ts`: 부분 실패·등록 ID·동시성·인증 전환을 관리한다. 로컬에는 사용자별 endpoint ID와 진행 상태만 보관한다. FCM 토큰은 앱이 별도 저장하거나 로그에 출력하지 않는다. Firebase SDK 자체의 토큰 메타데이터 관리는 별개다.
- `PushControls.tsx`·`PushBridge.tsx`: 설정 화면과 인증 후 수신·재확인을 연결한다. 서버 API는 `src/api/push.ts`에서 기존 쿠키·CSRF·재발급 정책을 사용한다.
- App `0.16.4`, Messaging `0.13.3`을 사용한다. 현재 서버가 FCM 등록 토큰을 요구하므로 `getToken`·`deleteToken`을 사용한다. 최신 [공식 API 문서](https://firebase.google.com/docs/reference/js/messaging)는 `getToken`을 obsolete로 표시하고 FID 기반 `register`를 권장한다. FID 전환에는 백엔드 계약 변경이 필요하다.

## 환경변수

[.env.example](../.env.example)의 공개 웹 앱 설정을 `.env.local`에 입력하고 개발 서버를 재시작하거나 배포용 빌드를 다시 생성한다.

```dotenv
VITE_FIREBASE_API_KEY=
VITE_FIREBASE_AUTH_DOMAIN=
VITE_FIREBASE_PROJECT_ID=
VITE_FIREBASE_MESSAGING_SENDER_ID=
VITE_FIREBASE_APP_ID=
VITE_FIREBASE_VAPID_KEY=
```

값이 누락되면 연결 버튼을 비활성화한다. Admin 서비스 계정·비공개 키는 포함하지 않는다. HTTPS 또는 localhost와 Push API·서비스 워커·Web Locks 지원이 필요하다. iOS는 [홈 화면 웹 앱의 지원 조건](https://webkit.org/blog/13878/)을 충족해야 하며 실제 기기 검증은 하지 않았다.

## 활성화·해제 흐름

1. 사용자 클릭에서 권한 요청 → 서비스 워커·FCM 토큰 확보 → POST `/api/users/me/notification-endpoints`의 200 `{ id, active: true }` 확인 → 최신 inAppEnabled를 유지한 설정 PUT → 전체 완료 표시 순서다.
2. 등록 후 설정 저장이 실패하면 endpoint ID를 pending 상태로 보존한다. 다시 연결하면 재등록·정리·설정 저장을 시도하며, 브라우저 해제로 정리할 수도 있다. 토큰 회전 시 새 ID를 등록하고 알려진 이전 ID를 해제한다.
3. **이 브라우저 해제**는 알려진 본인 endpoint에 DELETE 204를 확인하고 로컬 구독을 해제한다. 다른 기기의 사용자 설정은 끄지 않는다. **모든 기기 푸시 끄기**는 사용자 전체 pushEnabled를 false로 저장한 뒤 현재 브라우저의 알려진 등록을 정리한다.
4. 새로고침·설정 누락 시에도 전용 PushSubscription 해제를 시도한다. 현재 실행에서 토큰을 발급한 SDK 인스턴스가 있으면 deleteToken도 호출한다. SDK 삭제가 실패해도 브라우저 구독 폐기로 수신을 차단하며 SDK 메타데이터 정리는 다음 조회에 남을 수 있다.

같은 작업은 Promise를 공유하고 다른 작업·다른 탭의 동시 변경은 차단한다. 재로그인·로그아웃·확인된 계정 전환에서 이전 브라우저 구독을 정리한다. 로그아웃 전 알려진 본인 endpoint를 해제하며, 새 계정 쿠키로 이전 계정의 ID를 삭제하지 않는다. 권한 철회·토큰 변경은 앱 재진입과 포커스·명시적 재확인에서 점검하며 자동으로 권한을 요청하지 않는다.

포그라운드는 일반적인 새 알림 안내와 목록·미읽음 수 갱신만 수행한다. 백그라운드는 백엔드 WebpushConfig.notification에 대한 [SDK 자동 표시](https://firebase.google.com/docs/cloud-messaging/web/receive-messages)를 사용하므로 추가 showNotification을 호출하지 않는다.

푸시 클릭은 같은 출처의 검증된 상세 링크와 notificationId만 앱으로 전달한다. 기존 탭을 재사용하거나 `/notifications`를 연다. 로그인 확인 후 사용자가 공고 확인 버튼을 선택하면 기존 읽음·클릭 API를 수행하고 이동한다. 서비스 워커는 인증·CSRF API를 호출하지 않는다.

## 알려진 한계·검증 범위

- 엔드포인트 목록 API가 없어 로컬 ID를 잃으면 서버 등록을 정확히 조회·해제할 수 없다. 임의 ID를 사용하지 않는다. 다른 계정에 묶인 토큰의 409는 토큰을 새로 발급해 한 번만 재등록한다. 이전 서버 등록 정리는 별도로 남을 수 있다.
- 현재 발송 데이터에는 notificationId·messageId와 링크만 있어 이미 발송된 이전 계정 메시지를 완전히 식별할 수 없다. 수신자·세션 식별 및 백그라운드 자동 표시 정책은 백엔드와 추가 협의가 필요하다.
- 여러 기기의 설정 변경을 원자적으로 병합하는 서버 계약은 없다. 앱이 닫힌 동안의 토큰 회전은 다음 앱 재확인 전까지 서버 등록에 반영되지 않을 수 있다.
- 서버 발송 활성화 여부는 확인하지 않았다. 실제 검증에는 같은 Firebase 프로젝트·VAPID 키·허용된 로그인 계정·발송 환경과 서버 publicBaseUrl에 맞는 HTTPS 출처가 필요하다.
- 현재 Firebase 설정·인증 테스트 세션·발송 환경이 없어 실제 토큰 발급·서버 등록·푸시 수신·해제 통합은 **BLOCKED**다. 모의 테스트와 서비스 워커 핸들러 테스트는 실수신 성공을 의미하지 않는다.

최종 검증에서 `npm run lint`, `npm test`, `npm run build`(strict 타입 검사 포함)가 통과했다. 전체 227개 테스트가 통과했으며 실패·생략은 없다. Chrome 설정 누락 테스트는 이번 최종 검증에서 한 번 실행해 통과했다. 이는 실제 Firebase 권한·토큰·발송·수신 검증이 아니다.
