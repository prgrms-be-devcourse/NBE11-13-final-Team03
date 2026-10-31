# Y:ON Frontend

청년정책·직업훈련을 통합 탐색하고 개인화 추천과 변경 추적을 제공하는 Y:ON의 프런트엔드다. `/`는 홈, `/opportunities`는 통합 검색, `/opportunities/:id`는 기회 상세, `/login`은 카카오 로그인 진입 화면이다. 서버 사용자 조회에 기반한 인증 상태·로그아웃과 인증 후 홈 추천을 연결했다. `/mypage`에서 내 정보, `/mypage/profile`에서 맞춤 조건, `/mypage/interests`에서 관심 분야를 관리하며 `/onboarding`은 초기 설정 흐름이다. `/admin`은 `role=ADMIN` 사용자 전용 운영 화면이다([관리자 문서](docs/admin.md)).

## 기술 스택

- React 19, TypeScript 6, Vite 8
- React Router 7, TanStack Query 5, Axios, Lucide React
- CSS Modules, CSS 변수, Pretendard 웹폰트(CDN), ESLint

## 설치 및 실행

Node.js 24와 npm 11 환경에서 검증했다. `frontend/`에서 실행한다.

```sh
npm ci
npm run dev
```

터미널에 표시되는 개발 서버 주소로 접속한다.

```sh
npm run lint     # ESLint 검사
npm test         # API·홈·검색·상세·개발 프록시 테스트
npm run build    # TypeScript 검사 및 dist/ 빌드
npm run preview  # 빌드 결과 로컬 미리보기
```

## 디렉터리 구조

```text
frontend/
├── docs/api-auth.md    # API 인증 계약·구현·검증 문서
├── public/
│   ├── favicon.png
│   └── logo/yon-symbol.png
├── src/
│   ├── api/                      # 공통 클라이언트·오류·Query 설정 및 화면별 API
│   ├── auth/                     # 사용자 Query·인증 전환·복귀 경로
│   ├── components/
│   │   ├── ui/                   # Button, Card, TextField, StatusBadge, AsyncState
│   │   └── opportunities/        # PreferenceActions·공통 저장·숨김 UI
│   ├── layouts/                  # AppLayout·Header 및 CSS Modules
│   ├── pages/
│   │   ├── home/                 # 홈·개인화 추천
│   │   ├── auth/                 # 로그인·OAuth 완료 화면
│   │   ├── opportunities/        # 통합 검색·기회 상세
│   │   ├── profile/              # 마이페이지·맞춤 조건·관심 분야·온보딩
│   │   ├── notifications/        # 알림 목록·읽음 처리·설정
│   │   ├── saved/                # SavedOpportunitiesPage·저장 목록
│   │   └── PlaceholderPage.tsx   # 미구현 화면·404 안내
│   ├── routes/                   # AppRoutes
│   ├── App.tsx
│   ├── main.tsx                  # BrowserRouter·QueryClientProvider
│   ├── tokens.css
│   └── index.css
├── tests/              # API·화면·브라우저 모의·개발 프록시 테스트
├── .env.example
├── index.html
├── login-success.html
└── login-failure.html
```

디자인 토큰은 [src/tokens.css](src/tokens.css), 전역 스타일은 [src/index.css](src/index.css)에서 관리한다. 컴포넌트별 스타일은 해당 CSS Module에 둔다.

## 개발 프록시 및 환경 변수

개발 서버는 `/api` 요청을 기본적으로 `http://localhost:8080`으로 전달하며 경로를 rewrite하지 않는다. 주소를 바꾸려면 [.env.example](.env.example)을 `.env.local`로 복사하고 `BACKEND_URL`을 설정한 뒤 Vite를 재시작한다.

OAuth 시작·콜백 경로도 같은 백엔드로 전달한다. 프런트엔드 Host를 유지하며 카카오에 실제 접속 출처의 `/login/oauth2/code/kakao`를 등록해야 한다. 예를 들어 `http://localhost:5173/login/oauth2/code/kakao`다. localhost와 127.0.0.1은 혼용하지 않는다. 등록과 실제 로그인 통합은 별도 확인 대상이다.

`BACKEND_URL`은 개발 서버 전용이며 브라우저에 노출하는 `VITE_` 변수가 아니다. `npm run preview`와 실제 배포에는 이 프록시가 적용되지 않는다. 배포 시 프런트엔드 HTML·SPA 화면을 제공하고 같은 출처의 API·OAuth 경로를 백엔드로 연결하는 서버 설정이 필요하다.

## apiClient 사용

[src/api/client.ts](src/api/client.ts)의 `apiClient`를 사용한다. baseURL이 `/api`이므로 호출 경로에 `/api`를 다시 붙이지 않는다. 다음은 `src/` 바로 아래 파일에서 사용하는 예시다.

```ts
import { apiClient } from './api/client.ts'

// 공개 API
await apiClient.get('/opportunities')
// 인증이 필요한 API에만 requiresAuth를 지정한다.
await apiClient.get('/users/me', { requiresAuth: true })
```

## 구현 현황 및 검증 범위

**구현 완료**

검색·상세·홈에서 저장·해제, 개인화 추천에서 숨김, `/saved`에서 저장 목록을 제공한다.

로그인 사용자의 추천 카드 노출, 상세 진입, 공식 외부 링크 클릭을 진입 출처와 함께 행동 이벤트로 기록한다. 기준과 한계는 [행동 이벤트 문서](docs/behavior-events.md)를 참고한다.

통합 검색에서 인증 사용자용 신청 가능성 복수 선택 필터를 제공한다.

`/notifications`에서 알림 목록·읽음·클릭 처리, 헤더에서 미읽음 수, `/settings/notifications`에서 인앱 알림 설정을 제공한다. 계약과 검증 범위는 [알림 문서](docs/notifications.md)를 참고한다.

같은 설정 화면에 FCM 웹 푸시 연결·해제를 구현했다. 실제 Firebase 웹 앱 설정과 VAPID 공개 키가 없으면 연결을 비활성화한다. 환경변수와 제한 사항은 [FCM 문서](docs/fcm.md)를 참고한다.

공개 지역 API를 연결해 시·도부터 말단 지역까지 선택하고 프로필을 등록·수정할 수 있다. 지역 조회 실패·빈 목록에서는 신규 등록을 막고, 기존 지역을 유지한 다른 항목 수정과 관심 분야 설정은 허용한다.

**미구현**

숨김 해제는 조회 API 부재로 UI를 제공하지 않는다.

**실서버 미검증**

현재 지역 DB는 개발용 임시 데이터 약 10건으로 구성되어 일부 지역만 반환되는 것이 예상된 동작이다. 전국 지역 데이터 적재 후 전체 선택과 실제 프로필 저장을 추가 검증한다.

실제 카카오 로그인과 배포 통합은 미검증이다. 허용된 테스트 세션이 없어 인증된 저장·해제·숨김 요청과 DB 반영은 검증하지 않았다. 모의 테스트 통과는 실제 서버 인증 통합 성공을 의미하지 않는다.

인증된 알림 조회·읽음·클릭·설정 변경과 notification-service·DB 통합도 미검증이다.

Firebase 설정·로그인 테스트 세션·발송 환경이 없어 실제 FCM 토큰 발급·등록·수신·해제 통합은 BLOCKED다. 모의 테스트와 실제 푸시 수신 검증을 구분한다.

저장·숨김 계약과 캐시 동기화·검증 범위는 [저장·숨김 문서](docs/preferences.md)를 참고한다.

맞춤 조건·관심 분야 계약, 입력 정책과 지역 데이터 제한은 [프로필 문서](docs/profile.md)를 참고한다.

로그인 흐름, 인증 상태와 배포 협의 사항은 [인증 UI 문서](docs/auth.md)를 참고한다.

홈 구성, 추천 인증 조건과 후속 연결 사항은 [홈 문서](docs/home.md)를 참고한다.

검색 계약, URL 정책과 확인이 필요한 데이터는 [통합 검색 문서](docs/search.md)를 참고한다.

유형별 상세 표시 정책, 불확실한 조건과 기간 처리 및 미검증 범위는 [기회 상세 문서](docs/detail.md)를 참고한다.

인증·CSRF 계약, 오류 처리, 재발급 및 장애 복구 정책, 검증 범위와 후속 확인 사항은 [API 인증 기술 문서](docs/api-auth.md)를 참고한다.
