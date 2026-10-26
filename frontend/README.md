# Y:ON Frontend

청년정책·직업훈련을 통합 탐색하고 개인화 추천과 변경 추적을 제공하는 Y:ON의 프런트엔드다. `/`는 홈, `/opportunities`는 통합 검색, `/opportunities/:id`는 기회 상세, `/login`은 카카오 로그인 진입 화면이다. 서버 사용자 조회에 기반한 인증 상태·로그아웃과 인증 후 홈 추천을 연결했다. `/mypage`에서 내 정보, `/mypage/profile`에서 맞춤 조건, `/mypage/interests`에서 관심 분야를 관리하며 `/onboarding`은 초기 설정 흐름이다.

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
│   ├── api/            # apiClient, ApiError, queryClient, 인증·조회 API
│   ├── auth/           # 사용자 Query·인증 전환·안전한 복귀 경로
│   ├── components/ui/  # Button, Card, TextField, StatusBadge, AsyncState
│   ├── layouts/        # AppLayout, Header 및 CSS Modules
│   ├── pages/          # home, auth, opportunities 검색·상세, profile 설정, PlaceholderPage
│   ├── routes/         # AppRoutes
│   ├── App.tsx
│   ├── main.tsx        # BrowserRouter, QueryClientProvider
│   ├── tokens.css
│   └── index.css
├── tests/              # API 클라이언트 및 개발 프록시 테스트
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

## 현재 미구현 사항

저장·숨김 변경, 신청 가능성 검색 필터, 알림 기능은 구현하지 않았다. 계층형 지역 선택 UI는 외부 데이터 연결이 가능하도록 준비했으며 실제 API는 아직 연결하지 않았다. 현재 신규 프로필 등록 및 거주·학교 지역 변경은 제한하고, 기존 프로필의 지역 외 항목 수정과 관심 분야 설정은 사용할 수 있다. 저장 여부는 응답에 따른 읽기 전용 표시이며 저장 목록·알림 화면은 플레이스홀더다. 실제 카카오·브라우저 쿠키·배포 통합은 미검증이다.

맞춤 조건·관심 분야 계약, 입력 정책과 지역 데이터 제한은 [프로필 문서](docs/profile.md)를 참고한다.

로그인 흐름, 인증 상태와 배포 협의 사항은 [인증 UI 문서](docs/auth.md)를 참고한다.

홈 구성, 추천 인증 조건과 후속 연결 사항은 [홈 문서](docs/home.md)를 참고한다.

검색 계약, URL 정책과 확인이 필요한 데이터는 [통합 검색 문서](docs/search.md)를 참고한다.

유형별 상세 표시 정책, 불확실한 조건과 기간 처리 및 미검증 범위는 [기회 상세 문서](docs/detail.md)를 참고한다.

인증·CSRF 계약, 오류 처리, 재발급 및 장애 복구 정책, 검증 범위와 후속 확인 사항은 [API 인증 기술 문서](docs/api-auth.md)를 참고한다.
