# 홈

## 화면과 공개 기회

`/`는 서비스 소개·검색 진입, 최근 등록된 기회, 개인화 추천 안내로 구성한다. 기존 헤더·레이아웃·디자인 토큰·공통 UI를 유지하며 임의 공고나 통계를 추가하지 않는다.

- 히어로 검색은 기존 검색 파싱·직렬화를 재사용해 `/opportunities?keyword=...`로 이동한다. 공백 검색은 `/opportunities`로 이동한다. 탐색 CTA와 전체 보기 역시 실제 검색 화면으로 연결한다. 추천 CTA는 홈의 실제 추천 안내 영역으로 이동하며 임의 로그인 URL은 만들지 않는다.
- 공개 목록은 기존 `searchOpportunities`로 `GET /api/opportunities`를 호출한다. 조건은 `LATEST`, page=0, size=6이며 기존 직렬화 정책에 따라 기본 정렬·페이지는 생략한다. 최초 등록일 최신순이지 최근 변경순이나 개인화 추천이 아니다.
- 홈 키 `['home', 'opportunities', 'size=6']`는 검색 화면의 조건별 캐시와 구분한다. AbortSignal과 기존 QueryClient의 재시도·오류 정책을 사용한다.
- 공개 카드에는 검색 DTO의 유형·제목·분류·최초 등록일·실제 판정·저장 여부를 표시한다. 저장·해제 및 추천 카드의 숨김은 [저장·숨김 문서](preferences.md)를 따른다. 마감일·지역·지원금을 추측하거나 공고별 상세 API를 추가 호출하지 않는다. 제목 링크는 실제 상세 화면으로 연결한다.
- 로딩·정상·빈 목록·네트워크 오류·서버 오류를 구분하며 재시도는 refetch에 연결한다. 개발 프록시의 명시적인 연결 거부 코드가 있을 때만 백엔드 실행 상태 확인을 안내한다.

## 추천 계약

[RecommendationController](../../backend/src/main/java/com/team3/backend/recommendation/controller/RecommendationController.java), [Service](../../backend/src/main/java/com/team3/backend/recommendation/service/RecommendationService.java), [Item DTO](../../backend/src/main/java/com/team3/backend/recommendation/dto/RecommendationItemDto.java), [Reasons DTO](../../backend/src/main/java/com/team3/backend/recommendation/dto/RecommendationReasonsDto.java), 관련 선정·이유 생성 코드와 Controller·Service 테스트를 확인했다.

- `GET /api/users/me/recommendations`는 로그인과 판정 프로필이 필요하다. 비로그인은 401, 프로필 미등록은 404 `ELIGIBILITY_001`, 추천 없음은 200과 빈 배열이다. 일반적인 404를 프로필 미등록으로 처리하지 않는다.
- 최대 5개를 서버 선정 순서대로 반환한다. 최신 저장 판정만 사용하고 조회 중 재판정하지 않으며 숨긴 공고는 제외한다. 빈 결과가 신청 가능한 기회 자체가 없다는 뜻은 아니다.
- 관심 분야는 선택 사항이다. 미설정이면 분류 제한 없이, 설정했으면 서버의 분류명 매칭과 그룹 우선순위를 적용한다. 최신 판정이 ELIGIBLE 또는 POSSIBLY_ELIGIBLE인 후보만 선정하며 미판정·오래된 판정은 제외한다. 관심 일치 수준 자체는 응답 필드가 아니므로 서버가 제공한 이유·배지 외의 관심 정보를 추정하지 않는다.
- 현재 버전의 개인화 가능 또는 UNKNOWN 공고 중 정책은 OPEN·ALWAYS_OPEN, 훈련은 교육 시작일까지 최소 7일이 남은 후보를 서버에서 검토한다. 그룹 안의 기간·신규·ID 우선순위와 그룹별 배분도 서버 책임이며 프런트엔드는 응답을 재정렬하거나 부족한 건수를 채우지 않는다.
- `getRecommendations(signal)`은 기존 `apiClient`에서 `/users/me/recommendations`를 `requiresAuth: true`로 호출한다. 기존 인증 재발급 정책을 따르며 새로운 재시도·토큰 저장 로직을 만들지 않는다.
- 실제 DTO를 기준으로 응답 구조·enum·지역·추천 이유를 검사한다. 서버 이유의 badges(최대 3개)와 description을 그대로 표시한다. 프런트엔드 점수·이유·D-day는 계산하지 않는다.
- 정책의 `applicationEndDate`는 신청기간 마감일로, 훈련의 `trainingStartDate`는 교육 시작일로 구분한다. 모집 상태는 서버 값만 사용한다. 지역은 기존 상세 표시 규칙에 따라 역할·검증 상태·전국 여부를 구분하며 지역명은 추측하지 않는다.
- **확인된 계약 차이:** DTO 설명에는 상시 모집의 `applicationEndDate`가 null이라고 되어 있으나, [RecommendationCandidateService](../../backend/src/main/java/com/team3/backend/recommendation/service/RecommendationCandidateService.java)는 유효한 열린 고정 신청기간의 가장 가까운 종료일을 반환한다. 상시 기간과 고정 기간이 함께 있을 때도 값이 있을 수 있다. [RecommendationReasonService](../../backend/src/main/java/com/team3/backend/recommendation/service/RecommendationReasonService.java)는 별도의 상시 여부로 전역 마감 D-day 이유를 억제한다. 카드는 전체 공고의 최종 마감일로 단정하지 않고 여러 신청기간과 상세 확인을 안내한다. 전역 마감일 의미를 확정하려면 백엔드 설명·응답 의미를 먼저 정리해야 한다.

## 인증 전후 정책과 캐시

서버의 [내 정보 API](../../backend/src/main/java/com/team3/backend/user/controller/UserController.java)는 `GET /api/users/me`, [판정 프로필 API](../../backend/src/main/java/com/team3/backend/eligibility/controller/EligibilityProfileController.java)는 `GET /api/users/me/eligibility-profile`이다. 모두 보호 API다. 현재는 [전역 인증 상태](auth.md)가 내 정보 API를 한 Query로 공유한다. 홈에서 카드마다 사용자·프로필 API를 탐색적으로 호출하지 않는다.

Home은 인증이 확인된 경우에만 RecommendationsSection에 사용자 ID를 전달한다. 확인 중과 인증 확인 실패는 별도 상태로 표시하며 비로그인은 로그인 안내·실제 로그인 링크·공개 탐색 링크를 제공한다. 쿠키·저장된 판정·가짜 플래그로 로그인 상태를 추측하지 않는다. 비활성 키에 데이터가 있어도 추천 카드로 렌더링하지 않는다.

인증 상태 소유자가 서버에서 확인한 `confirmedUserId`를 전달하면 다음 구성이 활성화된다.

- 키는 `['recommendations', userId]`로 계정별 분리한다. 확인된 ID가 없으면 `enabled: false`이며 수동 refetch도 API 호출 전에 차단한다. ID는 조회 조건으로 서버에 전달하지 않으며 서버가 인증 쿠키로 사용자를 확인한다.
- 계정 변경 시 이전 데이터를 placeholder로 재사용하지 않는다. 이전 요청은 취소 신호로 종료하고 비활성 추천 캐시는 `gcTime: 0`으로 제거한다. 화면도 확인된 사용자 키의 결과만 사용한다.
- 로딩, 추천 결과, 빈 결과, 프로필 미설정, 인증 실패, 네트워크·서버 오류를 구분한다. 프로필 미등록 시 `/onboarding`으로 연결한다. 신규 등록의 지역 데이터 제한은 [프로필 문서](profile.md)를 따른다.
- 로그인·로그아웃·확인된 사용자 전환 시 인증 소유자가 추천을 비활성화하고 진행 중인 `['recommendations']` 요청을 취소·제거한다. 같은 사용자 ID로 재로그인해도 새 세션의 이전 개인화 캐시를 정리한다. `['home', 'opportunities']`, 검색·상세 캐시도 초기화·재조회한다. `resetAuthRecovery()`와 204 로그아웃의 세션 종료는 [인증 문서](auth.md)의 순서를 따른다. 실제 브라우저·여러 탭 전환과 서버 쿠키 동작은 별도 통합 검증 대상이다.

## 검증과 남은 범위

- Node 모의 테스트로 공개 최신 조회·키·요청 수·취소 신호, 검색 URL 인코딩, 추천 인증 옵션·응답 검증·순서·이유, 프로필 오류·일반 HTTP·네트워크 오류를 확인한다.
- 확인된 인증이 없을 때 QueryObserver가 추천 요청을 하지 않는지, 계정 전환 시 이전 데이터·늦은 응답을 재사용하지 않는지 확인한다.
- 실제 TSX 서버 렌더링으로 루트 라우트·히어로·카드 상세 링크·기본 접근성 속성, 공개 상태와 인증 후 추천 상태, 날짜 의미·추천 이유를 검증한다. 모의 데이터는 `tests/`에만 둔다.
- CSS는 모바일 한 열, 768px 이상 두 열, 1024px 이상 세 열과 터치 영역·줄바꿈을 구성하며 정적 테스트로 기준을 확인한다. 실제 PC·모바일 표시, 포커스·클릭, 가로 스크롤 및 브라우저 검색 이동을 검증한 것은 아니다.
- 홈 초기 구현 당시 실제 백엔드·브라우저·로그인 통합은 실행하지 않았다. 이후 프로필 UI와 공개 API 검증은 [프로필 문서](profile.md), 저장·숨김 연결은 [저장·숨김 문서](preferences.md)에 기록한다. 별도의 행동 이벤트 UI, 알림 및 백엔드·추천 알고리즘 변경은 범위에서 제외한다.

### 홈 추천 연동 재검증 (2026-10-26)

로컬 추천 Controller·Service·DTO·Security 설정을 대조했으며 실행 코드의 계약 불일치는 발견하지 못했다. 해당 추천·Security 코드는 로컬 `origin/main` 추적본 `7b2410d`와 차이가 없었다. 원격의 추가 갱신 여부는 확인하지 않았다.

홈 테스트에 5개 회귀 항목을 추가했다. nullable 필드·관심 분야 미설정 이유·최대 5건 순서, 인증 확인 중 이전 사용자 ID와 캐시가 남은 경우의 비노출, 실제 추천 QueryObserver 두 개의 조회 공유, 저장·해제·숨김 204 이후 재조회, 숨김 실패 시 기존 결과 유지와 후속 복구를 확인한다. 기존 PreferenceActions·preferences API·인증 정책은 변경하지 않았다.

`npm run lint`, `npm run build`(strict 타입 검사 포함)가 통과했다. `npm test -- --test-skip-pattern=Chrome`은 제외 옵션이 적용되지 않아 기존 모의 Chrome 테스트 3개도 실행됐으며, 기존 164개와 신규 5개를 합한 169개가 통과했다. Chrome 테스트의 HTTP는 모의 처리이며 실제 서버 인증 통합을 검증한 결과가 아니다.

실제 공개 GET `/api/opportunities?size=1`은 백엔드 `localhost:8080`과 개발 프록시 `localhost:5173`에서 모두 200을 반환했다. 허용된 로그인 테스트 세션이 없어 실제 추천 조회와 저장·해제·숨김 요청 및 DB 반영 검증은 BLOCKED다. 실제 모바일 시각 표시·키보드 조작과 인증된 서버의 추천 갱신은 수동 검증이 필요하다.
