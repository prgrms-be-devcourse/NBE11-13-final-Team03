# 로그인 사용자의 공개 조회 개인화 복구

## 문제

로그인 중인 사용자가 검색·상세·지역 목록 같은 공개 API를 호출했는데 찜 여부 등 개인화 정보가 조용히 빠지는 경우가 있다.
원인은 두 가지로 나뉜다.

| 경우 | 원인 | 담당 |
| --- | --- | --- |
| (a) 만료·잘못된 Access Token 쿠키가 전달된다 | 서버가 익명으로 통과시켜 비회원 응답을 준다 | 백엔드 안전망(401) |
| (b) Access Token 쿠키가 아예 없고 Refresh Token만 남아 있다 | 서버는 비회원 요청과 구분할 수 없다 | 프론트 복원·재발급 |

Access Token 쿠키의 Max-Age는 JWT 유효기간과 같으므로 (a)는 쿠키 만료와 토큰 만료 사이의 짧은 창(시계 차이·전송 지연)에서만 생긴다.
실제로 보고된 증상의 주 원인은 (b)다. (b)는 첫 진입뿐 아니라 로그인 상태로 앱을 오래 열어 둔 뒤 새 공개 조회를 할 때도 생긴다.
**주 수정은 프론트의 인증 복원·재발급·익명 대체이고, 백엔드는 (a)의 만료 토큰과 (b)의 Refresh Token만 남은 요청을 401로 알려
프론트가 재발급하게 하는 안전망이다.**

## 프론트: 인증 복원 순서와 공개 조회 복구

프론트 담당자가 설계한 기존 인증 복구 흐름(`requiresAuth`, `reissueAttempt`·`authGeneration`·`authSession` 가드,
`/users/me` 인증 Query, `resetPersonalizedQueries`)을 그대로 재사용한다. 새 재시도 체계는 추가하지 않는다.

### 순서 보장 (경우 b, 첫 진입)

검색(`OpportunitiesPage`), 홈 최신 목록(`HomePage`), 상세(`OpportunityDetailPage`)의 공개 조회 Query는
인증 상태가 `checking`인 동안 비활성이다. 인증 Query(`GET /users/me`, `requiresAuth: true`)가 먼저 나가
Access Token 쿠키가 없으면 401 → 재발급 → 새 쿠키를 받은 뒤에야 공개 조회가 시작된다.
인증 확인이 `error`로 끝나면 공개 조회는 비회원으로 진행한다.

### 오래 열어 둔 앱 (경우 b, 세션 중)

인증 Query가 `authenticated`로 캐시된 채 Access Token 쿠키만 만료되면 새 공개 조회는 바로 나간다.
이때 서버는 Refresh Token 쿠키만 있는 요청을 익명으로 답하지 않고 401 / `AUTH_006`을 내므로(아래 백엔드 항목),
`recoverAuth`가 재발급 후 같은 요청을 다시 보내 개인화가 유지된다. 프론트가 쿠키 만료 시각을 추측하지 않는다.

### `recoverAuth` 옵션 (경우 a와 창 밖의 만료)

`frontend/src/api/client.ts`의 공개 조회용 옵션이다. 기존 `requiresAuth`의 의미는 바꾸지 않는다.

| 상황 | `requiresAuth: true` (기존) | `recoverAuth: true` (추가) |
| --- | --- | --- |
| 401 + `AUTH_006`/`AUTH_007` | 재발급 후 같은 요청 1회 재전송 | 같음 |
| 재발급 401 (Refresh Token 무효) | 실패 유지, 재로그인 확인 전까지 재발급 고정 | 인증 손실을 한 번 알린 뒤 같은 요청을 **익명으로 1회 재전송**. 재발급 고정은 같음 |
| 재발급 네트워크·타임아웃·5xx·취소 | 오류 그대로 | 오류 그대로(익명으로 바꾸지 않는다) |
| 재발급 401 `AUTH_004` (다른 탭과의 재발급 충돌) | 그 탭의 새 쿠키로 같은 요청 1회 재전송. 실패로 고정하지 않는다 | 같음. 세션 상실이 아니므로 인증 손실을 알리지 않는다 |
| 동시 401 여러 개 | 재발급 1회 공유 | 같음 |
| 재발급 중 로그아웃·계정 전환 | 늦은 응답 `cancelled` | 같음 |

적용 대상: `GET /opportunities`(자격 필터가 있으면 기존대로 `requiresAuth`), `GET /opportunities/{id}`, `GET /regions`.
`GET /interests/options`는 서버가 토큰 검사에서 제외하므로 옵션을 붙이지 않는다.

인증 손실 알림(`onAuthLost`)은 `AuthProvider`가 `observeAuthLoss`로 구독한다. 알림을 받으면 인증 Query를 다시 확인하고,
그 결과가 비회원이면 기존 `queryFn`이 푸시 소유 정리와 개인화 캐시 초기화(`resetPersonalizedQueries`)를 수행한다.
인증 Query가 이미 확인 중이면 같은 재발급 실패를 공유하므로 추가 요청을 만들지 않는다.

## 백엔드: 만료 JWT 안전망

`TokenAuthenticationFilter`는 Access Token 쿠키가 있는 `/api/**` 요청에서 토큰이 만료·잘못됐으면 익명으로 통과시키지 않고
401 / `AUTH_007`로 응답한다. 토큰 없는 공개 조회는 기존대로 비회원 응답을 준다.

| 요청 | 처리 |
| --- | --- |
| 토큰 쿠키가 전혀 없는 공개 API | 기존 비회원 조회 유지 |
| Refresh Token 쿠키만 있는 공개 API | 401 / `AUTH_006`. 로그인 브라우저의 Access Token 쿠키 만료로 보고 프론트가 재발급한다 |
| Access Token 없는 보호 API | 기존 401 / `AUTH_006` 유지 |
| 만료·잘못된 Access Token이 있는 API | 401 / `AUTH_007`, 컨트롤러 실행 중단 |
| 유효한 Access Token이 있는 API | 인증 사용자로 처리, 기존 권한 검사 유지 |
| `/api/auth/reissue` | Access Token 검사 제외. Refresh Token·CSRF는 기존대로 검증 |
| `GET /api/interests/options` | 비개인화 공개 조회 및 프론트 CSRF 쿠키 bootstrap을 위해 Access Token 검사 제외 |
| `/api/internal/**` | 기존 내부 API Key 인증 유지 |
| OAuth·로그인·정적 리소스 등 `/api/` 밖의 경로 | 이 필터의 JWT 검사 제외. 기존 SecurityConfig 권한 규칙은 유지 |

필터의 예외 경로(`REISSUE_PATH`, `INTEREST_OPTIONS_PATH`, `INTERNAL_API_PREFIX`)는 `TokenAuthenticationFilter`의 상수이고
`SecurityConfig`의 permitAll·CSRF 예외 목록이 같은 상수를 참조한다. context path가 있어도 같은 기준으로 판정한다.

`CustomAuthenticationEntryPoint`를 사용해 에러 JSON 형식을 유지한다. 검증과 인증 객체 생성 사이에 만료되거나
claim 해석이 실패한 경우도 401로 처리한다. CSRF 검사는 완화하지 않는다.
Spring Security의 `csrf.spa()`가 발급하는 `XSRF-TOKEN` 쿠키와 `X-XSRF-TOKEN` 헤더 흐름은 변경하지 않는다.

`POST /api/auth/reissue`가 401로 실패하면(`AUTH_001`/`002`/`003`/`005`) Access Token·Refresh Token 쿠키를 함께 지운다.
그래야 프론트의 익명 재전송과 이후 공개 조회에 쓸모없는 쿠키가 붙어 401이 반복되지 않는다.
저장된 토큰과의 불일치(`AUTH_004`)는 예외다. 다른 탭의 동시 재발급에 진 경우라 브라우저가 이미 새 쿠키를 갖고 있을 수 있으며,
그 쿠키를 지우면 정상 세션이 끊긴다. 프론트도 `AUTH_004`를 확정 실패로 고정하지 않고 그 탭의 새 쿠키로 같은 요청을 다시 보내며,
그 쿠키가 나중에 만료되면 다시 재발급한다. 세션 상실로 확정된 401(`AUTH_001`/`002`/`003`/`005`)만 재로그인 확인 전까지 고정한다.

## 검증

2026-10-28 로컬에서 실행했다.

| 범위 | 결과 |
| --- | --- |
| `frontend`: `npm run lint`, `npm test`, `npm run build` | 통과. 테스트 298개 중 293 통과, 5개는 Chrome 미설치로 건너뜀 |
| `backend`: `com.team3.backend.auth.*`, `com.team3.backend.architecture.*` | 통과. 12개 클래스 85개 테스트, 실패·오류·건너뜀 0 |
| `backend` 전체 `bash gradlew test --no-daemon -q` | 통과(148개 클래스 1028개 테스트, 실패·오류 0, 건너뜀 98) |

프론트 회귀 테스트는 `frontend/tests/public-auth-recovery.test.mjs`에 있다. 검색 401(`AUTH_007`·`AUTH_006`)→재발급 200→재전송(찜 유지),
재발급 401→익명 재전송(찜 없음·인증 재확인·개인화 캐시 정리), 두 탭 재발급 충돌(`AUTH_004`)→새 쿠키로 재전송·다음 만료 시 재발급,
재발급 네트워크·타임아웃·5xx→오류 유지,
동시 요청 3개→재발급 1회, 재발급 중 로그아웃→늦은 응답 무시를 다룬다.

백엔드 회귀 테스트는 `SecurityIntegrationTest`·`TokenAuthenticationFilterTest`에 있다. 만료·잘못된 토큰의 401,
Refresh Token 쿠키만 있는 공개 조회의 401, 쿠키가 전혀 없는 비회원 공개 조회, 실제 CSRF 쿠키 bootstrap→Refresh Token 회전→
새 Access Token의 사용자 식별, 재발급 실패 시 쿠키 삭제(불일치는 유지), Refresh Token 오류 응답, CSRF 거부,
OAuth 진입·내부 키 인증 유지, context path 처리를 다룬다. Postgres 통합 테스트에는 Docker가 필요하다.

```bash
cd backend && bash gradlew test --no-daemon -q --tests 'com.team3.backend.auth.*' --tests 'com.team3.backend.architecture.*'
```

배포 환경의 실제 Kakao 로그인·CloudFront 쿠키 전달·검색/상세의 개인화 응답 복원은 별도 검증 대상이다.
