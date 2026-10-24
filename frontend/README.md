# Y:ON Frontend

청년정책·직업훈련을 통합 탐색하고 개인화 추천과 변경 추적을 제공하는 Y:ON의 프런트엔드다. 현재 기본 라우팅, 반응형 공통 레이아웃, 디자인 토큰, 공통 UI와 API 통신 기반을 구성했다. 각 경로는 플레이스홀더 화면이다.

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
npm test         # API 클라이언트 및 개발 프록시 테스트
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
│   ├── api/            # apiClient, ApiError, queryClient
│   ├── components/ui/  # Button, Card, TextField, StatusBadge, AsyncState
│   ├── layouts/        # AppLayout, Header 및 CSS Modules
│   ├── pages/          # PlaceholderPage
│   ├── routes/         # AppRoutes
│   ├── App.tsx
│   ├── main.tsx        # BrowserRouter, QueryClientProvider
│   ├── tokens.css
│   └── index.css
├── tests/              # API 클라이언트 및 개발 프록시 테스트
├── .env.example
└── index.html
```

디자인 토큰은 [src/tokens.css](src/tokens.css), 전역 스타일은 [src/index.css](src/index.css)에서 관리한다. 컴포넌트별 스타일은 해당 CSS Module에 둔다.

## 개발 프록시 및 환경 변수

개발 서버는 `/api` 요청을 기본적으로 `http://localhost:8080`으로 전달하며 경로를 rewrite하지 않는다. 주소를 바꾸려면 [.env.example](.env.example)을 `.env.local`로 복사하고 `BACKEND_URL`을 설정한 뒤 Vite를 재시작한다.

`BACKEND_URL`은 개발 서버 전용이며 브라우저에 노출하는 `VITE_` 변수가 아니다. `npm run preview`와 실제 배포에는 이 프록시가 적용되지 않는다. 배포 시 같은 출처의 `/api`를 백엔드로 연결하는 별도 서버 설정이 필요하다.

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

실제 서비스 페이지, 화면별 API 함수·쿼리 훅·데이터 연결, 로그인 UI·완료 흐름 및 OAuth 개발 프록시는 아직 구현하지 않았다. QueryClientProvider는 루트에 설정했으며, 인증 복구 초기화 함수와 로그인 성공 흐름의 연결은 후속 작업이다.

인증·CSRF 계약, 오류 처리, 재발급 및 장애 복구 정책, 검증 범위와 후속 확인 사항은 [API 인증 기술 문서](docs/api-auth.md)를 참고한다.
