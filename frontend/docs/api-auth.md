# API 통신 및 인증

## 1. 목적 및 적용 범위

Y:ON 프런트엔드 API 통신 기반의 백엔드 계약, CSRF 처리, 토큰 재발급과 장애 복구 정책을 설명한다. 개발 환경 실행 방법은 [프런트엔드 README](../README.md)를 참고한다.

현재 공통 클라이언트와 QueryClientProvider를 구성한 상태다. 실제 페이지의 API 함수·쿼리 훅·데이터 연결과 로그인 UI는 아직 구현하지 않았다. 이 문서의 코드·모의 테스트 확인 결과는 실제 브라우저 인증 통합을 보장하지 않는다.

## 2. 확인한 백엔드 인증·CSRF 계약

다음 실제 구현을 기준으로 확인했다.

| 근거 | 확인 내용 |
|---|---|
| [SecurityConfig](../../backend/src/main/java/com/team3/backend/auth/SecurityConfig.java) | `csrf.spa()`, 공개 API, 인증 요구사항, CSRF 예외 범위 |
| [AuthController](../../backend/src/main/java/com/team3/backend/auth/controller/AuthController.java), [TokenService](../../backend/src/main/java/com/team3/backend/auth/service/TokenService.java) | 재발급·로그아웃 요청과 응답, Refresh Token 회전 |
| [TokenCookieManager](../../backend/src/main/java/com/team3/backend/auth/cookie/TokenCookieManager.java), [AuthCookieProperties](../../backend/src/main/java/com/team3/backend/auth/cookie/AuthCookieProperties.java), [application.yaml](../../backend/src/main/resources/application.yaml) | 인증 쿠키 속성과 환경 변수, OAuth 콜백 |
| [TokenAuthenticationFilter](../../backend/src/main/java/com/team3/backend/auth/filter/TokenAuthenticationFilter.java), [CustomAuthenticationEntryPoint](../../backend/src/main/java/com/team3/backend/auth/security/CustomAuthenticationEntryPoint.java), [AuthErrorCode](../../backend/src/main/java/com/team3/backend/auth/exception/AuthErrorCode.java) | Access Token 검증과 인증 실패 응답 |
| [GlobalExceptionHandler](../../backend/src/main/java/com/team3/backend/global/exception/GlobalExceptionHandler.java), [ErrorResponse](../../backend/src/main/java/com/team3/backend/global/exception/ErrorResponse.java) | API 오류 응답 구조 |
| [InterestOptionController](../../backend/src/main/java/com/team3/backend/interest/controller/InterestOptionController.java) | CSRF 확보에 사용하는 공개 GET 경로 |

### 쿠키와 CSRF

Access Token과 Refresh Token의 쿠키 이름은 각각 `access_token`, `refresh_token`이다. 둘 다 HttpOnly이며 JavaScript에서 읽거나 localStorage/sessionStorage에 저장하지 않는다. 기본 인증 쿠키 속성은 Path=/, SameSite=Lax, Secure=false다. 서버의 `AUTH_COOKIE_SECURE`, `AUTH_COOKIE_SAME_SITE` 설정에 따라 달라지므로 배포 시 HTTPS·쿠키 정책을 별도로 확인해야 한다.

Spring Boot 4.1.1이 관리하는 Spring Security 7.1.1의 실제 `CsrfConfigurer.spa()` 구현은 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 설정한다. 기본 CSRF 쿠키 이름은 `XSRF-TOKEN`, 요청 헤더 이름은 `X-XSRF-TOKEN`이다. SPA 요청 핸들러는 토큰 발급을 유도하고 헤더의 원문 토큰을 검증한다. 이는 사용 중인 버전의 구현을 확인한 결과이며, `spa()`라는 이름만으로 추정한 계약이 아니다. CSRF 쿠키 저장소와 인증 쿠키의 `app.auth-cookie` 설정은 별개다.

CSRF 검사는 `/api/internal/**`만 제외한다. 해당 내부 API는 일반 프런트엔드 인증 경로가 아니며 CSRF 우회용으로 사용하지 않는다.

### 주요 HTTP 계약

| 요청 | 계약 |
|---|---|
| `GET /api/opportunities` | 비로그인 공개 조회다. |
| `GET /api/opportunities/{opportunityId}` | 비로그인 공개 상세 조회다. |
| `GET /api/interests/options` | 비로그인 공개 조회이며, CSRF 쿠키 확보에도 사용한다. |
| `POST /api/auth/reissue` | Refresh Token을 검증·회전하고 두 인증 쿠키를 다시 설정한다. 성공은 **200, 본문 없음**이다. `permitAll`이지만 CSRF 검사는 적용된다. |
| `POST /api/auth/logout` | 인증과 CSRF가 필요하다. 서버의 Refresh Token을 삭제하고 인증 쿠키를 제거한다. 성공은 **204**다. |

Access Token이 없으면 `AUTH_006`, 만료되거나 유효하지 않으면 `AUTH_007`로 401을 반환한다. 현재 만료와 변조는 별도 코드로 구분하지 않는다. Refresh Token의 누락·무효·만료·불일치·타입 오류는 `AUTH_001`부터 `AUTH_005`까지의 401 응답이다.

일반 API 오류 본문은 `{ code, message, fieldErrors }`다. Security 필터의 403은 이 형식을 보장하지 않으므로 403을 CSRF 오류나 인증 만료로 단정하지 않는다.

## 3. 프런트엔드 API 클라이언트 구조

| 파일·요소 | 책임 |
|---|---|
| [client.ts](../src/api/client.ts)의 `apiClient` | 일반 API 요청, CSRF 헤더, 제한적 인증 재발급과 재전송을 처리한다. |
| 같은 파일의 내부 `sessionClient` | CSRF 확보 GET과 재발급 POST를 처리한다. 인증 재시도 인터셉터를 붙이지 않아 재진입을 방지한다. |
| [ApiError.ts](../src/api/ApiError.ts) | 오류를 정규화하고 검증된 서버 오류 정보만 전달한다. |
| [queryClient.ts](../src/api/queryClient.ts) | 서버 상태 요청의 공통 재시도 옵션을 정의한다. |
| [main.tsx](../src/main.tsx) | 모듈에서 생성한 QueryClient를 루트 QueryClientProvider에 연결한다. |

두 Axios 인스턴스 모두 상대 baseURL `/api`, 쿠키 전송 `withCredentials: true`, 10초 timeout, `Accept: application/json`을 사용한다. 호출 경로는 `/opportunities`처럼 `/api`를 제외한 경로로 전달한다. 잘못된 경로·baseURL은 `configuration` 오류로 거부한다. Axios의 자동 XSRF 헤더 처리는 끄고 확인한 계약에 맞춰 상태 변경 요청에만 직접 헤더를 설정한다.

`ApiError`는 `http`, `network`, `timeout`, `cancelled`, `csrf`, `configuration`, `unknown` 종류와 HTTP 상태·서버 코드·필드 오류를 제공한다. 서버 본문은 `{ code, message, fieldErrors }` 형식을 검사한 후 사용한다. 빈 본문·HTML·예상과 다른 구조도 HTTP 상태를 유지해 일반 오류로 처리한다. 타임아웃 코드는 `ECONNABORTED`, `ETIMEDOUT`으로 구분한다.

원본 AxiosError의 요청 설정·헤더·본문·cause는 보관하거나 로깅하지 않는다. 서버의 `message`와 `fieldErrors`는 전달하므로 서버에서 민감 정보를 포함하지 않아야 한다.

QueryClient는 4xx·CSRF·취소 등 오류를 자동 재시도하지 않는다. `ApiError`의 네트워크·timeout·5xx 오류만 한 번 더 시도하며, mutation은 자동 재시도하지 않는다. Axios의 인증 재발급과 QueryClient의 일반 요청 재시도는 별도 책임이다. 범용 API 훅이나 UI 상태 관리는 이 계층에 추가하지 않았다.

## 4. CSRF 확보 및 요청 처리 흐름

1. GET·HEAD·OPTIONS·TRACE는 안전한 메서드로 처리하며 CSRF 확보 요청이나 헤더를 추가하지 않는다.
2. 상태 변경 요청 전 `document.cookie`에서 현재 `XSRF-TOKEN`을 읽는다. 인증 토큰 쿠키는 읽지 않는다.
3. CSRF 쿠키가 없거나 값을 해석할 수 없으면 실제 공개 API인 `GET /api/interests/options`를 호출해 발급을 유도한다. 병렬 요청은 하나의 `csrfBootstrap` Promise를 공유한다.
4. GET이 성공하면 쿠키를 다시 읽고 `X-XSRF-TOKEN` 헤더로 전달한다. 그래도 읽을 수 없으면 `csrf` 오류로 종료하며 상태 변경 요청은 보내지 않는다.
5. 확보 GET이 실패하면 해당 오류로 종료한다. 한 요청에서 확보 GET을 무한 반복하지 않으며, 공유 Promise는 완료 후 해제한다.

CSRF 토큰 값 자체를 캐싱하지 않는다. 로그인이나 재발급으로 쿠키가 바뀌거나 삭제되면 다음 상태 변경 요청은 그 시점의 쿠키를 사용한다. 재전송할 때도 요청 인터셉터를 다시 거친다. 403 응답은 원인을 단정하거나 자동 재시도하지 않는다.

## 5. 401 재발급 및 동시성 제어

자동 재발급은 다음 조건을 모두 만족할 때만 시도한다.

- 호출자가 인증 API에 `requiresAuth: true`를 지정했다.
- 응답이 401이며 서버 코드가 `AUTH_006` 또는 `AUTH_007`이다.
- 원래 요청을 아직 재전송하지 않았다.
- 요청이 `/auth/reissue`나 `/auth/logout` 자체가 아니다.

`requiresAuth`는 쿠키 전송이나 서버의 인증 검사 여부가 아니라, 401 발생 시 자동 토큰 재발급을 허용하는 옵션이다. 불필요한 재발급을 방지하고 호출부에서 재발급 의도를 명시하기 위해 기본값을 `false`로 유지한다. 다만 인증 API에서 옵션이 누락되면 세션 만료 시 자동 재발급이 수행되지 않으므로, 후속 API 함수 구현 및 코드 리뷰에서 `requiresAuth: true` 지정 여부를 확인한다.

공개 API의 401, 일반 네트워크 오류, 403 등은 이 조건으로 재발급하지 않는다. 재발급용 내부 클라이언트도 자동 재발급에 재진입하지 않는다. 재발급 성공 후 원래 요청은 최대 한 번만 재전송한다. 재전송도 401이면 종료한다.

### 상태와 요청의 관계

| 상태 | 역할 |
|---|---|
| `authGeneration` | 성공한 재발급 또는 인증 복구 초기화 이후 이전 요청과 현재 토큰 상태를 구분한다. |
| `authSession` | 확인된 재로그인 뒤 초기화할 때 이전 사용자 세션의 요청을 구분한다. 서버의 세션 ID와는 별개의 클라이언트 내부 값이다. |
| `reissueAttempt` | 인증 세대·세션, 공유 Promise와 재발급 취소용 AbortController를 보관한다. |
| 요청의 `_reissueAttempt` | 요청 시작 시 참조를 고정해 이후 실패·복구 중에도 자신의 시도 결과를 사용한다. |
| 요청의 `_authRetried` | 원래 요청의 재전송을 한 번으로 제한한다. |

같은 시도를 참조한 병렬 401은 재발급 한 번의 결과를 공유한다. 이미 재발급이 성공해 인증 세대가 바뀌었다면, 이전 요청의 늦은 401은 추가 재발급 없이 갱신된 쿠키로 재전송한다. 이전 세션의 401은 새 세션에서 재전송하지 않는다. 실패한 시도와 새 요청의 관계는 다음 복구 정책을 따른다.

## 6. 일시적 장애 발생과 복구 정책

### 문제 상황과 발생 원인

기존 구현은 재발급 또는 그 이전 CSRF 확보가 한 번 실패하면 reject된 `reissueAttempt.promise`를 같은 인증 세대에 계속 보관했다. 성공할 때만 인증 세대가 바뀌므로 네트워크가 회복돼도 새 인증 요청이 과거 실패를 재사용했다. CSRF 확보 GET만 실패했을 때는 재발급 POST를 한 번도 보내지 않았어도 복구가 차단됐다.

### 검토한 위험 및 트레이드오프

실패 직후 공유 Promise를 무조건 제거하면 실패 전 출발한 요청의 지연된 401들이 새 재발급을 각각 만들 수 있다. 반대로 모든 실패를 영구 보관하면 일시적인 통신 장애에서도 복구하지 못한다. 일정 시간 후 무조건 재시도하는 방식은 기존 요청과 새 요청을 구분하지 못한다.

따라서 기존 요청은 자신의 실패 결과를 공유하되, 일시적 실패가 끝난 후 시작한 새 요청만 다음 시도에 참여하게 하는 방식을 선택했다. 타이머나 일괄 Promise 초기화는 사용하지 않는다.

### 선택한 해결 방법

| 결과·상황 | 상태 전이와 동작 |
|---|---|
| 재발급 성공 | 인증 세대를 증가시키고 다음 시도 객체를 준비한다. 대기 요청은 최대 한 번 재전송한다. |
| 네트워크·timeout·HTTP 500~599 | 일시적 실패로 분류한다. CSRF 확보 단계의 같은 오류도 포함한다. 기존 시도의 실패 Promise는 유지하고, 새 요청을 위한 별도 시도 객체를 준비한다. |
| 일시적 실패 전 출발한 요청의 늦은 401 | 인증 세대가 같다면 자신이 참조한 이전 실패로 종료한다. 다음 시도가 진행 중이어도 새 재발급을 만들지 않는다. |
| 실패 후 시작한 새 인증 요청 | 다음 시도 객체를 참조하며 재발급을 다시 시도할 수 있다. 병렬 요청은 그 결과를 공유한다. |
| 재발급 401·403 등 비일시적 실패 | 같은 인증 상태에서 실패를 계속 공유해 자동 반복을 막는다. 403의 원인을 인증 만료로 단정하지 않는다. |
| 확인된 재로그인 후 `resetAuthRecovery()` 호출 | 인증 세션·세대를 변경하고 이전 실패를 해제한다. 진행 중인 이전 재발급을 취소하며 이전 세션의 지연된 401이 새 사용자 세션에서 재전송되지 않게 한다. |

`resetAuthRecovery()`는 서버에서 재로그인 성공을 확인한 뒤에만 호출한다. 단순 401이나 네트워크 오류 때 호출해서는 안 된다. 이 함수 자체가 로그인하거나 쿠키를 발급하는 것은 아니다. 실제 로그인 완료 흐름과의 연결은 후속 로그인 구현에서 수행한다.

### 회귀 테스트 결과

[api.test.mjs](../tests/api.test.mjs)에서 네트워크 오류·타임아웃·503 각각에 대해 5개 대기 요청의 동일 실패 공유, 실패 전 요청의 늦은 401 종료, 실패 후 새 요청의 재발급 성공을 검증했다. CSRF 확보 중 timeout 뒤 복구, 확정적 인증 실패 뒤 재로그인 초기화, 초기화 시 이전 재발급 취소, 최대 한 번 재전송, 공개 API·로그아웃·CSRF 403 유지도 검증했다. 직전 Step 6-2 실행에서는 API 테스트 16개가 통과했다.

### 남은 한계

여러 탭 사이에서 시도를 공유하지 않는다. timeout 전에 서버가 이미 Refresh Token을 회전했지만 브라우저가 새 쿠키를 받지 못했다면, 다음 시도가 401로 끝나 재로그인이 필요할 수 있다. 요청 취소도 서버에서 완료된 회전을 되돌리지 못한다. 이 동작은 실제 백엔드·브라우저에서 확인해야 한다.

## 7. OAuth 로그인 연동 시 확인할 사항

로그인 시작은 `/oauth2/authorization/kakao`, 콜백은 `/login/oauth2/code/kakao`다. [OAuth2SuccessHandler](../../backend/src/main/java/com/team3/backend/auth/oauth2/OAuth2SuccessHandler.java)와 [OAuth2FailureHandler](../../backend/src/main/java/com/team3/backend/auth/oauth2/OAuth2FailureHandler.java)는 각각 `/login-success.html`, `/login-failure.html`로 이동한다. 현재 백엔드 리소스에는 해당 HTML이 없고 프런트엔드에도 완료 경로가 없다.

로그인 UI와 OAuth 개발 프록시는 아직 추가하지 않았다. 로그인 구현 전에 리다이렉트 목적지와 카카오에 등록할 콜백 출처를 백엔드와 합의해야 한다. 확인된 로그인 성공 시점에 `resetAuthRecovery()`를 연결하고, 실제 쿠키 적용 및 이전 요청 정리도 함께 검증해야 한다.

## 8. 검증 결과와 미검증 범위

직전 Step 6-2에서 실행한 검증 기록은 다음과 같다. 이번 문서 분리 작업에서는 코드를 바꾸지 않았으며 아래 테스트·빌드를 다시 실행하지 않았다.

| 검증 | 직전 결과·범위 |
|---|---|
| `npm test` | API 16개와 프록시 1개, 총 17개 통과다. |
| `npm run lint` | 통과했다. |
| `npm run build` | strict TypeScript 검사와 Production build가 통과했다. |
| [api.test.mjs](../tests/api.test.mjs) | 모의 Axios adapter와 쿠키 문자열로 요청·오류·CSRF·동시성·복구 상태 전이를 검증한다. |
| [proxy.test.mjs](../tests/proxy.test.mjs) | 로컬 모의 upstream을 사용해 기본·환경 변수 대상 주소, `/api` 경로 유지와 쿠키 헤더 전달을 검증한다. |

모의 테스트는 실제 백엔드 로그인, 브라우저의 HttpOnly·SameSite·Secure·Path 적용, OAuth 통합을 대신하지 않는다. 실제 네트워크에서 timeout 전에 토큰 회전이 완료되는 상황, 배포 프록시·HTTPS 환경 및 여러 탭의 동작도 미검증이다. 확인하지 않은 성능 개선 수치나 통합 성공 결과는 제시하지 않는다.

## 9. 알려진 한계 및 후속 개선 사항

- 실제 화면의 API 함수·쿼리 훅·데이터 연결, 로그인 UI·완료 경로와 초기화 함수 연결은 후속 작업이다.
- 후속 로그인 구현에서는 서버의 로그인 성공과 인증 쿠키 적용을 확인한 뒤 `resetAuthRecovery()`를 호출해야 한다. 단순 로그인 화면 진입·리다이렉트 시작·401·통신 오류를 성공으로 간주해 호출하지 않는다. 이전 실패 해제와 이전 세션의 재발급 취소·지연된 401 차단을 로그인 완료 흐름에서도 검증해야 한다.
- 전용 CSRF 발급 API가 없어 공개 관심 분야 조회에 의존한다. 필요하면 백엔드에서 공개 토큰 발급 경로를 제공하는 방안을 검토한다. 현재 처리를 위해 CSRF 보호를 끄거나 예외 경로를 추가할 필요는 없다.
- Security 필터의 403을 일관된 JSON `ErrorResponse`로 제공하는 방안을 검토한다. 프런트엔드는 현재도 다른 형식의 403을 처리한다.
- 여러 브라우저 탭 사이의 재발급 조율과 응답 유실 시 Refresh Token 회전 문제는 별도 검토가 필요하다.
- [Vite 설정](../vite.config.ts)의 `/api` 프록시는 개발 서버 전용이다. 기본 대상은 `http://localhost:8080`이며 [.env.example](../.env.example)의 `BACKEND_URL`로 변경한다. 경로 rewrite나 브라우저용 `VITE_` 변수를 사용하지 않는다. preview·실제 배포에는 별도 동일 출처 `/api` 서버 연결이 필요하다.
- 배포 환경의 HTTPS·쿠키 속성과 OAuth 콜백 출처를 실제 브라우저에서 확인해야 한다.
