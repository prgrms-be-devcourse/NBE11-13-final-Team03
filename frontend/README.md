# Y:ON Frontend

청년정책·직업훈련을 통합 탐색하고 개인화 추천과 변경 추적을 제공하는 Y:ON의 프런트엔드입니다. 현재는 기본 라우팅, 반응형 공통 레이아웃, 디자인 토큰과 공통 UI 컴포넌트를 구성한 단계이며, 각 경로는 플레이스홀더 화면입니다.

## 기술 스택

- React 19, TypeScript 6, Vite 8
- React Router 7: 클라이언트 라우팅
- TanStack Query 5: 루트 QueryClientProvider 구성
- Axios: 설치 완료, 통신 설정은 추후 구성
- Lucide React: 아이콘
- CSS Modules, CSS 변수, Pretendard 웹폰트(CDN)
- ESLint

## 설치 및 실행

Node.js 24와 npm 11 환경에서 검증했습니다. `frontend/`에서 실행합니다.

```sh
npm ci
npm run dev
```

터미널에 표시되는 개발 서버 주소로 접속합니다.

```sh
npm run lint     # ESLint 검사
npm run build    # TypeScript 검사 및 dist/ 빌드
npm run preview  # 빌드 결과 로컬 미리보기
```

## 디렉터리 구조

```text
frontend/
├── public/
│   ├── favicon.png
│   └── logo/yon-symbol.png
├── src/
│   ├── components/ui/  # Button, Card, TextField, StatusBadge, AsyncState
│   ├── layouts/       # AppLayout, Header 및 CSS Modules
│   ├── pages/         # PlaceholderPage
│   ├── routes/        # AppRoutes
│   ├── App.tsx
│   ├── main.tsx       # BrowserRouter, QueryClientProvider
│   ├── tokens.css
│   └── index.css
└── index.html
```

## 스타일과 백엔드 연동

디자인 토큰은 `src/tokens.css`, 전역 스타일은 `src/index.css`에서 관리합니다. 각 컴포넌트의 스타일은 해당 CSS Module에 둡니다.

백엔드 API 연동은 추후 구성 예정입니다. Vite API 프록시, Axios 인스턴스·인터셉터, 인증·CSRF 처리는 아직 설정하지 않았습니다.
