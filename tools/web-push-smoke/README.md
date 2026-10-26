# 독립 웹 푸시 수신 테스트

프론트 브랜치·backend 로그인 없이 알림 권한, FCM 토큰 발급, Firebase 콘솔 테스트 메시지 수신을 확인하는 임시 화면이다.
실제 Firebase 값은 커밋하지 않고 화면에 입력한다. 웹 설정 5개와 VAPID **공개 키**만 사용한다.
서비스 계정 JSON·비공개 키·내부 API 키는 필요하지 않으며 이 화면에 입력하지 않는다.
설정/토큰/수신 내역을 별도 서버로 수집하지 않는다. Firebase SDK 통신과 브라우저 IndexedDB/서비스 워커 저장은 발생한다.
페이지 새로고침만으로 FCM 등록이 해제되지는 않는다.

## 1. 로컬 화면

```bash
cd /Users/mac/Desktop/teamProject/NBE11-13-final-Team03/tools/web-push-smoke/public
python3 -m http.server 4173 --bind 127.0.0.1
```

Chrome 일반 창에서 http://localhost:4173 을 연다. Python은 설치된 3.x 런타임을 사용한다.
file:// 주소나 다른 PC의 HTTP 사설 IP 주소는 사용하지 않는다.
localhost에서 토큰 발급과 Firebase 콘솔 수신은 가능하다. 우리 Notification 서버의 HTTPS origin 가드를 우회하는 기능은 없다.

## 2. 공유 HTTPS 테스트 주소

Firebase Hosting의 **별도 테스트 사이트**를 사용한다. 기존 서비스 사이트를 덮어쓰지 않는다.
계정 로그인과 사이트 생성/배포는 실행 담당자가 수행한다.

```bash
cd /Users/mac/Desktop/teamProject/NBE11-13-final-Team03/tools/web-push-smoke
npx firebase-tools login
npx firebase-tools hosting:sites:list --project FIREBASE_PROJECT_ID
npx firebase-tools hosting:sites:create UNIQUE_TEST_SITE_ID --project FIREBASE_PROJECT_ID
npx firebase-tools target:apply hosting smoke UNIQUE_TEST_SITE_ID --project FIREBASE_PROJECT_ID
npx firebase-tools deploy --only hosting:smoke --project FIREBASE_PROJECT_ID
```

`FIREBASE_PROJECT_ID`는 앱의 projectId, `UNIQUE_TEST_SITE_ID`는 직접 정하는 전역 고유 사이트 ID로 바꾼다.
이미 마련한 전용 테스트 사이트가 있다면 sites:create는 생략하고 그 사이트를 target에 연결한다.
이 폴더의 firebase.json은 smoke target만 배포한다. Node.js/npm과 Firebase 프로젝트 권한이 필요하다.
배포 출력의 Hosting URL을 팀원에게 전달한다. 현재 파일은 배포 전이므로 실제 HTTPS 주소는 아직 없다.
앱 서비스 워커와 충돌하지 않게 전용 사이트에서만 사용한다.

## 3. 수신 확인

1. 같은 Firebase 프로젝트의 웹 앱 설정(apiKey, authDomain, projectId, messagingSenderId, appId)과 Web Push VAPID 공개 키를 확인한다.
2. 화면에 입력하고 토큰 발급 버튼을 누른다. 사이트 알림 권한을 허용한다.
3. FCM 토큰을 복사한다. 테스트 담당자에게만 전달하고 공개 이슈/로그에 남기지 않는다.
4. Firebase 콘솔 → Messaging → 알림 메시지 작성 → 테스트 메시지 전송에서 토큰을 넣고 발송한다. 메뉴 이름은 UI 버전에 따라 다를 수 있다.
5. 전경에서는 화면 수신 기록, 다른 탭으로 이동한 배경에서는 브라우저/OS 알림을 확인한다. OS 집중 모드·알림 허용도 확인한다.
6. 종료 시 토큰·서비스 워커 해제 버튼을 누른다. 새로고침 후라 세션이 없다면 해당 테스트 사이트의 브라우저 저장 데이터를 지운다.

Notification 서버의 NOTIFICATION_PUSH_ENABLED 활성화 없이 테스트한다.
실제 Endpoint 등록·동의 저장·인앱·Outbox·SQS·슬롯 발송·우리 서비스 알림 클릭 이동은 별도 통합 테스트다.
이 화면은 카카오 로그인이나 Notification 내부 API를 공개하지 않는다.

## 참고

- https://firebase.google.com/docs/cloud-messaging/web/get-started
- https://firebase.google.com/docs/cloud-messaging/web/receive-messages
- https://firebase.google.com/docs/hosting/multisites

## 확인한 범위

2026-10-26 KST: JavaScript 문법, Hosting smoke target, 로컬 정적 파일 HTTP 응답과 브라우저 화면 표시를 확인했다.
합성 데이터로 서비스 워커의 notification payload 중복 표시 방지·data-only 표시·페이지 전달을 확인했다.
실제 프로젝트의 토큰 발급·FCM 수신·Hosting 배포는 아직 수행하지 않았다. SDK는 공식 CDN 13.0.0을 사용한다.
