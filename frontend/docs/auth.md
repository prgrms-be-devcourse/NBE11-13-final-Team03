# 로그인과 인증 상태

## 범위와 확인한 계약

[SecurityConfig](../../backend/src/main/java/com/team3/backend/auth/SecurityConfig.java), [OAuth2SuccessHandler](../../backend/src/main/java/com/team3/backend/auth/oauth2/OAuth2SuccessHandler.java), [OAuth2FailureHandler](../../backend/src/main/java/com/team3/backend/auth/oauth2/OAuth2FailureHandler.java), [쿠키 관리자](../../backend/src/main/java/com/team3/backend/auth/cookie/TokenCookieManager.java), [UserController](../../backend/src/main/java/com/team3/backend/user/controller/UserController.java), UserMeResponseDto·Role·사용자 관련 테스트 및 기존 API 인증 문서를 확인했다. 백엔드는 수정하지 않는다.

| 요청·경로 | 실제 처리 |
|---|---|
| `/login` | 프런트엔드 로그인 안내와 실제 카카오 시작 링크다. |
| `GET /oauth2/authorization/kakao` | 백엔드 OAuth 시작이다. 커스텀 resolver는 계정 선택 prompt를 추가한다. |
| `/login/oauth2/code/kakao` | 백엔드가 카카오 코드를 처리하는 콜백이다. 프런트엔드에서 코드를 교환하지 않는다. |
| `/login-success.html` | 서버가 인증 쿠키 설정 후 리다이렉트하는 프런트엔드 진입점이다. 사용자 조회 성공 전에는 로그인 완료가 아니다. |
| `/login-failure.html` | 일반적인 실패·재시도 안내다. 오류 쿼리의 내부 정보를 그대로 표시하지 않는다. |
| `GET /api/users/me` | id, nullable nickname·email, USER/ADMIN role을 확인한다. 쿠키 유무로 상태를 추정하지 않는다. |
| `POST /api/auth/logout` | 인증·CSRF가 필요하다. 204에서 서버 Refresh Token·쿠키 제거가 확인된다. |

JWT는 HttpOnly 쿠키로만 사용하며 JavaScript에서 읽거나 저장·삭제하지 않는다. 기존 CSRF 확보·현재 쿠키 읽기·자동 재발급·10초 timeout을 재사용한다. `requiresAuth`는 서버 인증을 켜는 옵션이 아니라 401 재발급 허용 옵션이다.

## 인증 상태와 로그인 완료

`AuthProvider`가 단일 `['auth', 'user']` Query를 소유한다. checking / authenticated / anonymous / error를 구분하고 헤더·홈에서 같은 상태를 사용한다. Context는 사용자 정보를 별도로 복제하지 않으며, 앱 전체 OAuth·로그아웃 진행 상태와 중복 요청을 조율하기 위해 사용한다.

일반 사용자 조회는 `requiresAuth: true`로 기존 제한적 재발급을 사용한다. 최종 401만 비로그인으로 처리한다. 네트워크·timeout·5xx·403·잘못된 응답은 인증 확인 실패이며 로그아웃으로 단정하지 않는다. 확인 중에는 추천을 비활성화한다. 헤더에서 다시 확인할 수 있고 공개 홈·검색·상세는 계속 제공한다. 사용자 정보는 30초간 fresh이며 이후 기본 창 포커스 정책에 따라 재확인할 수 있다.

OAuth 완료 흐름은 다음과 같다.

1. 완료 경로에서는 일반 사용자 Query를 비활성화한다. 진행 중인 사용자 조회도 취소한다.
2. `verifyOAuthUser()`가 새 쿠키로 `GET /users/me`를 직접 요청한다. **이 확인 요청만 `requiresAuth: false`**로 자동 재발급을 생략한다. 서버의 인증 검사는 그대로 적용된다. 기존 reject된 재발급 Promise를 참조해 확인이 영구 실패하는 순환을 피하기 위한 예외다.
3. HTTP 200과 실제 사용자 DTO가 확인된 뒤에만 `resetAuthRecovery()`를 호출한다. 성공 경로 진입·버튼 클릭·401·통신 오류만으로 호출하지 않는다.
4. 이전 개인화 요청·캐시를 정리하고 사용자 Query를 새 정보로 설정한다. 저장된 안전한 복귀 경로로 replace 이동한다.
5. 실패하면 완료를 주장하지 않고 다시 확인·카카오 로그인 재진입·공개 탐색을 제공한다. 완료 페이지에서는 헤더의 일반 사용자 재조회 버튼을 제공하지 않으며 화면의 전용 재시도로 위 완료 절차 전체를 다시 수행한다. 일반 사용자 Query의 200만으로 OAuth mutation·복귀 처리를 우회하지 않는다. 요청 자체는 기존 timeout으로 제한되고, 중복 완료 호출은 같은 작업 Promise를 공유한다.

최초 자동 요청과 수동 재시도는 같은 화면별 completion runner를 사용한다. 활성 요청 번호와 화면 생존 여부로 성공·오류 콜백을 검사하며, 언마운트·요청 무효화 뒤에는 navigate·복귀 경로 소비·로컬 오류 갱신을 하지 않는다. 이전 요청의 finally도 새 요청의 pending 상태를 비우지 않는다. StrictMode의 cleanup/setup에서는 새 화면 요청만 콜백을 처리한다. 화면을 떠났다는 이유로 이미 확인된 서버 인증 성공이나 전역 사용자 캐시 갱신을 되돌리지는 않는다.

## 로그아웃과 캐시

로그아웃 중에는 추천을 중단하고 중복 클릭을 막는다. 인증·개인화 Query를 취소한 뒤 기존 apiClient로 POST한다. `/auth/logout` 자체는 기존 정책상 재발급하지 않는다. CSRF 쿠키가 없으면 공개 옵션 GET으로 확보하며, 쿠키를 읽지 못하면 우회하지 않고 실패한다.

정확한 204 응답 뒤 `endAuthSession()`이 이전 인증 세션의 재발급을 취소하고 지연된 401 재전송을 막는다. 이는 서버 로그아웃 확인용이며 로그인 성공을 의미하는 `resetAuthRecovery()`와 호출 목적을 구분한다. 내부 세션·세대 전이만 공유하고 새로운 인터셉터는 추가하지 않는다. 이후 개인화 캐시를 정리하고 사용자 Query를 null로 설정한 뒤 공개 홈으로 이동한다.

401·403·잘못된 성공 응답이면 로그아웃 성공으로 처리하지 않는다. 네트워크·시간 초과·완료 응답 확인 불가 오류는 서버 완료 여부가 불확실하다. 이때 mutation을 pending으로 유지해 추천을 중단하고, 개인화 캐시를 정리한 뒤 `fetchQuery`의 staleTime을 0으로 지정해 실제 `/users/me` 재확인을 수행한다. 과거 fresh 사용자 캐시만으로 인증을 다시 확정하지 않는다. 전용 재발급 코드를 추가하지 않고 기존 사용자 조회의 제한적 재발급 정책을 유지한다.

재확인 200이면 확인된 사용자로, 최종 401이면 비로그인으로 전환한다. 재확인도 실패하면 Query의 error 상태를 유지하고 추천을 중단한다. 자동 재활성화가 중복 재확인을 만들지 않도록 막되 헤더의 명시적 재확인으로 복구할 수 있다. 마지막 사용자 데이터가 캐시에 남아 있어도 error 상태에서는 인증 사용자 정보로 제공하지 않는다. 서버 재확인 결과와 별개로 원래 로그아웃 mutation은 오류로 종료한다. 비로그인 재확인만으로 로그아웃 204 성공이나 성공 후 홈 이동을 대신 실행하지 않는다.

로그인 성공, 로그아웃 성공, 사용자 조회에서 확인된 ID 변경·비로그인 전환 시 다음을 수행한다.

- 추천 요청을 취소하고 `['recommendations']` 캐시를 제거한다. 전환 중 보호 API를 자동 재조회하지 않는다.
- 맞춤 조건 `['profile', userId]`와 관심 분야 `['user-interests', userId]`도 취소·제거한다. 설정 저장은 확인된 사용자 객체·인증 전환 mutation·화면 취소 신호를 검사해 이전 응답을 새 계정 캐시에 반영하지 않는다.
- `['opportunities']`, `['opportunity-detail']`, `['home', 'opportunities']`는 취소 후 reset해 활성 observer의 과거 판정·저장 여부도 비운다. 활성 공개 Query는 현재 쿠키로 다시 조회한다. 공개 분류 옵션 캐시는 유지한다.
- 추천은 확인된 사용자 ID 키만 활성화한다. 동일 ID 재로그인도 OAuth 성공 시 개인화 캐시를 정리한다. 취소한 이전 응답으로 과거 데이터가 복원되지 않도록 Query 취소 신호를 유지한다.

프로필 미등록 404 `ELIGIBILITY_001`, 추천 빈 배열, 인증 실패와 일반 오류를 구분한다. 프로필 수정과 온보딩은 [프로필 문서](profile.md)를 따른다. [저장·숨김](preferences.md)은 기존 인증 흐름을 재사용하며 저장 목록 캐시도 인증 전환 때 취소·제거한다. [알림](notifications.md)의 목록·미읽음 수·설정 캐시도 사용자별로 구분하고 인증 전환 때 취소·제거한다.

### 모든 기기 로그아웃

마이페이지 `계정 관리` 카드에만 둔다. 헤더와 일반 로그아웃은 그대로다.

- **API:** `POST /api/auth/logout-all`(`postLogoutAll`). `requiresAuth`·CSRF를 쓰고, 204만 완료로 본다.
  - 서버는 이 사용자의 Refresh Token을 모두 지우고 현재 브라우저 쿠키를 만료한다.
  - 다른 기기는 이미 받은 Access Token이 만료될 때까지 유지되고, 그 뒤 재발급이 거부된다.
  - 다른 기기의 푸시 엔드포인트는 알림 서버가 `USER_SESSION_ENDED`로 비활성화한다.
- **전환 흐름:** `createAuthActions`의 같은 세션 종료 흐름(`logout-all` 종류)을 쓴다.
  - 순서: 이 브라우저 푸시 해제 → 요청 → `endAuthSession` → 개인화·관리자 캐시 정리 → 인증 null
  - 푸시 정리 실패는 요청을 막지 않는다.
  - 성공하면 마이페이지에 완료 상태와 `다시 로그인하기`를 보여 준다. 일반 로그아웃처럼 페이지를 떠나지 않는다.
- **확인 절차:** 회원 탈퇴와 같은 인라인 확인 그룹이다(붉은색 없음).
  - 열면 열기 버튼을 감추고 확인 영역으로 포커스를 옮긴다.
  - 취소·Escape는 요청 없이 닫고 포커스를 열기 버튼으로 돌려준다.
- **결과 불확실:** 네트워크 오류·시간 초과·204가 아닌 성공 응답은 실패로 단정하지 않는다.
  - 개인화 캐시를 비우고 서버에서 로그인 상태를 다시 확인한다.
  - 자동으로 다시 보내지 않는다.
  - 로그아웃 상태로 확인돼 카드가 사라져도 안내는 페이지 위에 남긴다.
- **동시 실행 방지:** `AuthProvider`는 세션 종료 전환(로그아웃·모든 기기 로그아웃·탈퇴)이 진행 중이면 다른 종료 요청을 새 mutation으로 시작하지 않는다.
  - 헤더 로그아웃을 눌러도 무시되고, 오류 배너도 띄우지 않는다.
  - 마이페이지의 다른 종료 버튼은 비활성화한다.
- **검증:** `tests/push.test.mjs`(전환 흐름), `tests/profile-ui.test.mjs`(배치·상태), `tests/logout-all-browser.test.mjs`(실제 Chrome, HTTP 모의)
  - 실제 여러 기기 간 세션 무효화와 다른 기기의 재발급 거부는 백엔드·실기기 통합 검증이 필요하다.

## 안전한 복귀 경로

헤더 로그인 링크는 현재 내부 탐색 경로를 `returnTo`로 전달한다. 실제 OAuth 이동 전에 검증된 경로만 sessionStorage의 `yon.oauth.return`에 보관한다. 인증 토큰·사용자 정보는 저장하지 않는다. 저장이 차단되면 홈으로 복귀한다.

알려진 내부 화면만 허용한다. 외부 URL, `//`, 역슬래시·제어문자, 로그인·OAuth 콜백 경로, 알 수 없는 경로는 홈으로 대체한다. 검색 조건은 기존 파싱·직렬화의 허용 항목만 남기며 다른 경로의 쿼리·fragment는 제거한다. 토큰 파라미터를 복귀 URL에 전달하지 않는다. 사용자 확인 성공 후 저장 값을 한 번 소비한다.

## 개발 프록시와 배포 협의

- `/api`는 기존 BACKEND_URL(기본 `http://localhost:8080`) 대상·쿠키 전달을 유지한다.
- OAuth 시작 `/oauth2/authorization`와 콜백 `/login/oauth2/code`는 같은 백엔드로 전달하되 `changeOrigin: false`, `xfwd: true`로 프런트엔드 Host·출처 정보를 유지한다. 백엔드의 `{baseUrl}` 기반 callback이 8080으로 빠지는 것을 막기 위한 개발 설정이다. `/login` 화면과 완료 HTML은 프런트엔드가 제공한다.
- `login-success.html`, `login-failure.html`은 실제 소스 파일과 Vite 다중 HTML 빌드 진입점이다. SPA fallback만 가정하지 않으며 빌드 결과에도 별도 파일로 생성한다.
- 카카오 개발자 콘솔에 **실제 사용하는 프런트엔드 출처**의 callback을 등록해야 한다. 예: `http://localhost:5173/login/oauth2/code/kakao`. localhost와 127.0.0.1은 혼용하지 않는다. 이 등록·실제 카카오 연결은 확인하지 않았다.
- 현재 저장소 Docker nginx는 모든 `/` 요청을 백엔드로 전달하므로 이 프런트엔드를 제공하는 배포 설정이 아니다. 운영에서는 프런트엔드 dist·완료 HTML·SPA 경로를 제공하고 `/api`, OAuth 시작·콜백만 백엔드로 전달하도록 서버 담당자와 구성해야 한다. 기존 파일은 수정하지 않는다.
- 운영 HTTPS에서는 신뢰하는 프록시의 Host·Proto 전달과 Spring forwarded-header 적용을 합의해야 한다. application.yaml의 `{baseUrl}` 콜백이 실제 외부 HTTPS 출처를 사용해야 한다. 필요하면 서버의 forwarded-header 전략·리다이렉트 설정을 담당자가 구성해야 한다.
- 인증 쿠키 기본 Path=/, SameSite=Lax, Secure=false와 별도의 CSRF 쿠키 설정을 실제 출처·HTTPS 환경에서 확인해야 한다. OAuth 중간 JSESSIONID·state와 인증·CSRF 쿠키가 프록시를 거쳐 이어지는지도 브라우저 검증 대상이다.

## 검증 범위와 한계

모의 Axios·Query 테스트로 사용자 응답 검증, 최종 401과 통신 실패 구분, 과거 실패한 재발급에서 OAuth 후 복구, 성공 확인 전 초기화 금지, 중복 완료·로그아웃 공유, CSRF 로그아웃, 실패 시 상태 보존, 이전 세션 401 차단, 개인화 캐시 정리·늦은 응답 차단, 안전한 복귀 경로와 저장 차단을 확인한다.

`auth-recovery.test.mjs`는 Provider에서 실제 사용하는 Query 활성화·상태 판정 함수를 QueryObserver·MutationObserver와 실제 API/session 작업에 연결한다. OAuth 실패 후 전체 재시도, 과거 재발급 실패 복구, fresh 캐시에서 로그아웃 응답 유실 후 서버 재확인(401·200·네트워크 실패·timeout), 확인 중 추천 미호출과 계정 전환을 검증한다. 실제 인증 작업을 기다리는 completion runner가 페이지 이탈 후 복귀 경로를 소비하지 않으며, 늦은 성공·오류가 새 요청을 덮어쓰지 않는 것도 검증한다. React DOM의 실제 마운트·클릭·탐색 통합을 검증한 것은 아니다.

실제 TSX 서버 렌더링으로 헤더 네 상태·로그인/완료/실패 화면·추천 활성화·공개 접근 유지와 기본 접근성 속성을 확인한다. 모의 upstream 프록시 테스트는 시작 URL·Host·콜백 쿠키 헤더·리다이렉트·명시적 HTML 제공을 확인한다. 쿠키 헤더 전달 테스트는 브라우저가 실제 쿠키를 수락했다는 검증이 아니다.

실제 백엔드·카카오 OAuth·브라우저 통합은 실행하지 않는다. 클릭·포커스·반응형 시각 표시, 실제 쿠키 적용·회전·삭제, 로그인 취소·재로그인, 배포 프록시와 여러 탭 간 전환은 미검증이다. 탭 간 즉시 전환 이벤트는 공유하지 않으며 창 포커스 재확인에 의존한다. 요청 취소가 이미 서버에서 완료된 토큰 회전이나 전송된 Set-Cookie를 되돌리지는 못한다.
