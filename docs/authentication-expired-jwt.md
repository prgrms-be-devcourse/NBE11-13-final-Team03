# 공개 API의 만료 JWT 처리

## 변경 목적

만료되거나 잘못된 Access Token을 익명 요청으로 처리하면, 로그인 중인 사용자의 검색·상세 응답에서
찜 여부나 개인화 정보가 조용히 사라진다. Access Token을 보낸 API 요청은 인증 실패를 명시해
프론트가 재발급 후 원래 요청을 다시 보낼 수 있도록 한다.

## 응답 계약

| 요청 | 처리 |
| --- | --- |
| Access Token 없는 공개 API | 기존 비회원 조회 유지 |
| Access Token 없는 보호 API | 기존 401 / `AUTH_006` 유지 |
| 만료·잘못된 Access Token이 있는 API | 401 / `AUTH_007`, 컨트롤러 실행 중단 |
| 유효한 Access Token이 있는 API | 인증 사용자로 처리, 기존 권한 검사 유지 |
| `/api/auth/reissue` | Access Token 검사 제외. Refresh Token·CSRF는 기존대로 검증 |
| `GET /api/interests/options` | 비개인화 공개 조회 및 프론트 CSRF 쿠키 bootstrap을 위해 Access Token 검사 제외 |
| `/api/internal/**` | 기존 내부 API Key 인증 유지 |
| OAuth·로그인·정적 리소스 등 `/api/` 밖의 경로 | 이 필터의 JWT 검사 제외. 기존 SecurityConfig 권한 규칙은 유지 |

`TokenAuthenticationFilter`는 기존 `CustomAuthenticationEntryPoint`를 사용해 에러 JSON 형식을 유지한다.
Access Token 검증과 인증 객체 생성 사이에 만료되거나 JWT claim 해석이 실패한 경우도 401로 처리한다.
CSRF 검사는 완화하지 않는다. CSRF가 먼저 거부하는 상태 변경 요청은 기존처럼 403일 수 있다.
기존 Spring Security 7.1.1의 `csrf.spa()`는 프론트가 읽을 수 있는 `XSRF-TOKEN` 쿠키를 발급한다.
프론트는 상태 변경 요청에서 그 값을 `X-XSRF-TOKEN` 헤더로 보낸다. 이 설정은 변경하지 않는다.
회귀 테스트는 `csrf()` 테스트 도우미의 저장소 교체에 의존하지 않고 실제 공개 GET의 쿠키·헤더를 사용한다.

## 프론트 연동 조건

- 기존 클라이언트는 `requiresAuth: true`인 요청의 `AUTH_006`·`AUTH_007`에만 재발급 후 1회 재시도한다.
  공개 검색·상세도 로그인 사용자의 개인화가 필요하면 해당 요청에서 이 옵션을 적용해야 한다.
  이 PR은 프론트 코드를 수정하지 않는다.
- 재발급 요청 자체는 재발급 인터셉터를 적용하지 않아 무한 반복을 막는다.
- CSRF 쿠키가 없을 때 기존 클라이언트는 `GET /api/interests/options`로 쿠키를 받은 뒤 재발급한다.
  이 경로는 만료 Access Token이 있어도 접근할 수 있어야 한다.
- Access Token 쿠키의 수명은 JWT 유효기간과 같으므로 만료 후 쿠키가 아예 전달되지 않을 수 있다.
  이 경우 공개 API의 비회원 요청과 서버가 구분하지 않는다. 기존 `/api/users/me` 기반 로그인 복원 및
  프론트의 인증 상태·재발급 흐름으로 처리한다. Refresh Token 존재만으로 공개 요청을 일괄 거부하지 않는다.
- 로그아웃·FCM Endpoint 정리, 쿠키 수명·Secure/SameSite 설정은 이 변경 범위가 아니다.

## 검증

2026-10-27 로컬에서 아래 범위 14개 클래스 85개 테스트가 통과했다. 실패·오류·건너뜀은 모두 0이고
`bootJar`도 성공했다. 전체 backend 회귀 테스트는 실행하지 않았다.

| 범위 | 테스트 수 |
| --- | ---: |
| SecurityIntegrationTest | 25 |
| TokenAuthenticationFilterTest | 9 |
| 기존 인증·쿠키·OAuth·재발급 동시성 테스트 | 28 |
| 아키텍처 경계 테스트 | 15 |
| 알림 사용자 API Facade·회원 탈퇴 통합 테스트 | 8 |

저장소의 `backend/`에서 재현한다.

```bash
./gradlew --offline test \
  --tests 'com.team3.backend.auth.*' \
  --tests 'com.team3.backend.user.controller.UserNotificationFacadeControllerTest' \
  --tests 'com.team3.backend.user.service.UserWithdrawalIntegrationTest' \
  --tests 'com.team3.backend.architecture.*' \
  bootJar
```

Postgres 통합 테스트에는 Docker가 필요하다. Gradle 의존성이 없는 환경에서는 `--offline`을 제외한다.
검증에는 만료·잘못된 토큰의 401, 비회원 공개 조회, 실제 CSRF 쿠키 bootstrap→Refresh Token 회전→
새 Access Token의 사용자 식별, Refresh Token 오류 응답, CSRF 거부, OAuth 진입·내부 키 인증 유지가 포함된다.

배포 환경의 실제 Kakao 로그인·CloudFront 쿠키 전달·검색/상세의 개인화 응답 복원은 별도 검증 대상이다.
