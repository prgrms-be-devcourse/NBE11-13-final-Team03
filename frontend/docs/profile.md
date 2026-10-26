# 마이페이지·맞춤 조건·관심 분야

## 화면과 접근

`/mypage`는 인증 Context의 서버 확인 사용자 정보(닉네임·이메일·계정 유형), 프로필·관심 분야 등록 상태, 설정 진입, 기존 204 계약의 로그아웃을 제공한다. 기본 회원 정보 편집은 제공하지 않는다. `/mypage/profile`과 `/mypage/interests`는 각각 편집 화면이다. `/onboarding`은 맞춤 조건 → 선택적 관심 분야 → 완료·홈 이동 흐름이며 기존 프로필을 인식한다. 저장은 단계별로 실행하며 화면 이탈 시 이미 서버에 저장된 값을 삭제하거나 되돌리지 않는다. 관심 분야 단계에서 이전 단계로 돌아갈 수 있고, 저장 중에는 단계 이동을 막는다. 단계 이동으로 폼이 다시 열리면 서버에 저장한 값을 사용하며 저장하지 않은 초안은 보관하지 않는다. 프로필이 없는 동안 완료 화면으로 진행하지 않는다. 지역 선택이 불가능해도 별도의 관심 분야 설정 링크는 사용할 수 있다.

확인 중·인증 확인 실패·비로그인 상태를 구분하고, 인증된 경우에만 보호 Query와 폼을 마운트한다. 공개 홈·검색·상세는 기존대로 유지한다. 로그인 복귀 경로에는 실제 추가한 두 설정 경로만 허용한다.

## 확인한 API 계약

- `GET /api/users/me`: 기존 인증 Query의 id, nickname, email, role을 사용한다. 닉네임·이메일 편집 API는 없다. 최신 UserController에 회원 탈퇴 DELETE는 있으나 이번 구현 범위에서 제외한다.
- `GET /api/users/me/eligibility-profile`: 200은 현재 프로필, 404 **ELIGIBILITY_001만** 미등록으로 처리한다. 다른 404·401·403·네트워크·서버 오류는 미등록으로 바꾸지 않는다.
- `PUT /api/users/me/eligibility-profile`: 최초 등록·전체 교체이며 생략한 선택값은 null이다. API 함수와 모의 PUT 테스트를 구현했으나 지역 선택지 부재로 신규 등록 버튼은 비활성화한다.
- `PATCH /api/users/me/eligibility-profile`: JSON Merge Patch 계약이다. 수정한 최상위 필드만 보내며, untouched 필드는 생략하고 명시적으로 비운 선택은 null로 보낸다. 중첩 응답 묶음은 해당 필드 전체를 보낸다. 서버 구현은 일반 JSON body를 받는다.
- `GET/PUT /api/users/me/interests`: `{ interests: [{ type, code }] }` 응답·요청이다. PUT은 전체 교체, 빈 배열은 전체 해제다. 최대 30개, 동일 type/code 중복을 허용하지 않는다.
- `GET /api/interests/options`: 검색의 기존 API 함수·`['interest-options']` Query를 재사용한다. category → CATEGORY, subCategory → SUB_CATEGORY, detailCategories의 문자열 → DETAIL_CATEGORY다. 문자열은 가공하지 않는다. NCS 타입은 원본 코드이며 옵션 API는 원본 NCS 코드 목록을 제공하지 않는다.

[EligibilityProfileController](../../backend/src/main/java/com/team3/backend/eligibility/controller/EligibilityProfileController.java), UpdateRequest·Response·ProfileAnswers, Validator·Service·관련 DTO 및 Validator 테스트, [UserInterestController](../../backend/src/main/java/com/team3/backend/interest/controller/UserInterestController.java)·Service·테스트와 [InterestOptionController](../../backend/src/main/java/com/team3/backend/interest/controller/InterestOptionController.java)·Service를 기준으로 구현했다. 모든 보호 요청은 `requiresAuth: true`이며 기존 CSRF·재발급 정책을 사용한다.

## 입력과 저장 정책

필수 정보는 생년월일(1900-01-01부터 한국 시간 오늘), 거주지역, 고용 상태, 최종 학력이다. enum 코드와 한국어 표시명은 분리한다. 선택 항목은 접을 수 있는 학적·전공, 혼인·주거, 소득·학자금, 특화 대상·병역, 훈련 영역으로 묶는다. 마이페이지 요약에는 생년월일·소득·특화 대상의 실제 값을 재노출하지 않는다.

- null은 미입력, UNKNOWN은 모름이다. 소득에는 NOT_APPLICABLE을 허용하지 않으며 소득 없음은 PROVIDED + 0원 + 월/연 단위다. 원 단위 정수 최대 999999999999999를 허용한다.
- 중위소득은 0~999.99%, 소수 둘째 자리까지, 학자금 지원구간은 1~10이다. PROVIDED일 때만 수치가 필요하다. 두 항목은 UNKNOWN·NOT_APPLICABLE도 허용한다.
- 가구원 수는 1~99명이다. 특화 대상은 PROVIDED일 때 하나 이상을 선택하며, 다른 상태로 바꾸면 대상 배열을 비운다. 병역 개월은 복무 완료·복무 중일 때만 0 이상의 Java Integer 범위 정수로 보낼 수 있다.
- 상태 변경 시 종속 값을 비워 잘못된 짝을 보내지 않는다. 입력 오류는 연결 가능한 필드에 안내하며 서버 내부 메시지 대신 안전한 안내를 표시한다. 중복 저장은 pending 상태 및 동기 실행 가드로 차단한다. 자동 mutation 재시도는 없다.
- 관심 분야는 실제 계층을 표시하고 각각 선택한다. 상위를 해제해도 하위가 자동 삭제되지 않는다. 기존 NCS·옵션에 없는 값은 체크된 별도 영역에서 유지하며 사용자가 직접 해제한다. 옵션 로딩·오류·빈 목록을 구분하며 오류 시 자유 입력으로 대체하거나 전체 교체 저장을 허용하지 않는다.

## 지역 선택 구조와 연결 경계

지역 Controller·정적 코드/이름/상위 코드 리소스는 기존 점검에서 확인되지 않았다. [RegionDirectory](../../backend/src/main/java/com/team3/backend/region/directory/RegionDirectory.java)는 DB RegionRepository에서 색인을 만든다. 프로필 Service는 거주지를 사전에 존재하면서 하위 지역이 없는 코드(세종 포함)로 제한한다. 학교 지역은 사전에 존재하는 코드면 된다. Region 엔티티는 실제 10자리 region_cd를 문자열로 저장한다.

외부 데이터를 받는 [RegionSelect](../src/pages/profile/RegionSelect.tsx)를 거주지·학교에 공통 사용한다. 컴포넌트 내부 HTTP 요청은 없다. source props로 unavailable/loading/error/ready 상태와 재시도 콜백을 전달하며, ready의 빈 목록과 잘못된 계층은 별도로 표시한다. 기본 HTML select·label·fieldset·legend와 오류 설명 연결, 전역 키보드 포커스 스타일을 사용한다. 기존 모바일 1열 그리드를 재사용한다.

- [regions.ts](../src/api/regions.ts)의 `RegionOptionDto`는 **예정 필드** code/name/parentCode/leaf만 나타낸다. 실제 URL, 인증 조건, 배열·객체 래퍼는 미확정이다. GET `/api/regions` 요청과 지역 Query는 구현하거나 실행하지 않는다.
- `toRegionOptions(rows)`는 추후 실제 응답에서 꺼낸 행 목록만 변환한다. DTO와 별개인 내부 모델은 value/label/parentValue/isLeaf다. 코드·이름을 변경하거나 숫자로 변환하지 않는다.
- [regionSelection.ts](../src/pages/profile/regionSelection.ts)는 완전한 시·도 → 시·군·구 2단계 목록을 검증한다. 중복 코드, 누락된 부모, 순환·자기 참조, 3단계 이상, 말단 부모의 자식, 자식 없는 비말단, 말단이 아닌 하위 행은 목록 전체를 선택 불가로 처리한다. 잘못된 행을 임의로 최상위로 올리거나 일부 목록을 정상 목록처럼 사용하지 않는다. 향후 API가 분할·페이지 조회를 제공하면 전체 목록을 모은 뒤 전달하거나 확인된 계약에 맞춰 이 경계를 조정해야 한다.
- 거주지는 isLeaf=true만 확정한다. 비말단 시·도를 선택하면 기존 하위 코드가 null로 초기화되고 하위 선택을 기다린다. 학교는 사전에 존재하는 비말단 시·도도 확정할 수 있으며 하위 선택은 선택 사항이다.
- 세종시 3611000000이 최상위 leaf=true로 전달되면 추가 시·군·구 없이 확정한다. 코드별 예외 처리나 가짜 하위 행을 추가하지 않는다. 다른 최상위 말단 지역도 같은 규칙으로 동작한다.
- 기존 코드가 현재 목록에 없으면 코드와 유지 안내를 표시하고 다른 지역을 자동 선택하지 않는다. 로딩·오류·빈/잘못된 목록도 기존 값을 변경하지 않는다. 서버에서 읽은 **변경 없는 기존 코드**는 PATCH에서 생략해 지역 외 항목 수정을 허용한다. 단, 정상 목록에서 기존 거주지가 명확히 비말단으로 확인되면 저장을 차단한다. 신규·변경 코드는 정상 목록의 선택 기준을 통과해야 하며, 컴포넌트 표시와 별도로 제출 시에도 검증한다.

현재 페이지는 `unavailableRegions`를 전달한다. 따라서 신규 등록과 거주·학교 지역 변경, 신규 사용자 온보딩 첫 단계는 여전히 제한된다. 관심 분야 설정과 기존 지역을 유지한 다른 프로필 항목 수정은 제한하지 않는다. fixture는 `tests/`에만 두며 운영 화면에 지역 목록을 하드코딩하지 않는다.

실제 연결 시에는 확정된 Controller·DTO·Security 계약을 확인하고 `src/api/regions.ts`에 응답 추출·검증과 apiClient 요청을 추가한다. 기존 Query 패턴으로 로딩·오류·재시도를 `RegionSource`로 변환해 `ProfileSettingsPage`·`OnboardingPage`의 `regions` props에 전달한다. ProfileForm과 RegionSelect는 이미 같은 props를 받으므로 폼 전체를 다시 구성하지 않는다. 서버 leaf가 실제 하위 지역 유무와 일치하는지 확인하고, 세종·학교 시·도·목록 누락·오류 복구·인증된 PUT/PATCH를 통합 검증한다.

## Query와 인증 격리

프로필은 `['profile', userId]`, 관심 분야는 `['user-interests', userId]`로 분리하고 gcTime=0으로 화면 이탈 후 민감 데이터를 불필요하게 보관하지 않는다. 저장 성공 시 해당 Query의 서버 응답을 반영하고 추천·검색·상세·홈 공개 목록만 invalidate한다. 전체 캐시를 삭제하지 않으며 공개 분류 옵션을 유지한다. 프로필 변경 후 판정 결과는 서버의 커밋 후 비동기 갱신이므로 즉시 최신 판정을 보장하지 않는다. 임의 타이머·폴링은 추가하지 않는다.

기존 인증 전환 정리에 두 사용자별 캐시를 포함한다. 저장 직전·응답 후 사용자 객체 동일성, 인증 확인/전환 진행 여부, AbortSignal을 검사한다. 화면 이탈 시 요청을 취소하고 이전 저장 응답을 새 사용자 캐시·온보딩 완료 상태로 반영하지 않는다. 서버에서 이미 끝난 저장은 취소로 되돌릴 수 없다. 응답 유실 후에는 재조회로 반영 여부를 확인해야 한다.

## 검증과 남은 범위

이전 마이페이지 구현 작업(2026-10-26)에서 로컬 실제 백엔드에 읽기 요청을 실행했다. `/api/opportunities?size=1`, `/api/interests/options`는 HTTP 200(분류 8개), 세 사용자 API(`/users/me`, `/users/me/eligibility-profile`, `/users/me/interests`)는 인증 쿠키 없이 모두 401이었다. 테스트 계정·로그인 세션을 확보하지 않았으므로 실제 보호 API 200과 PUT/PATCH는 검증하지 않았다. DB나 실제 사용자 정보를 변경하지 않았다. 기존 개발 프록시는 기본 localhost:8080을 사용하며 설정을 변경하지 않았다. 이번 지역 UI 준비 작업에서는 실제 API 요청을 실행하지 않는다.

실제 `getInterestOptions`·`searchOpportunities` 함수로도 백엔드 응답 검증을 통과했다(분류 8개·검색 1개). Node 실행에는 브라우저 출처가 없으므로 상대 `/api` 설정을 유지하고 HTTP 전송 adapter에서만 실제 upstream을 지정했다. baseURL을 절대 주소로 변경하는 시도는 기존 경로 보호 규칙에 의해 차단됐다. 이는 실제 브라우저·Vite 프록시 통합 확인을 대신하지 않는다.

Node 모의 API·QueryObserver·MutationObserver 테스트는 미등록/오류 구분, DTO·enum·조건부 범위 검증, 최초 PUT·차이 PATCH, 생략/null, 관심 분야 전체 교체·기존 NCS 보존·중복·30개 제한, CSRF/401/403/400/5xx, 선택적 Query 갱신, 계정 전환 및 지연 응답, 화면 이탈 취소를 검증한다. 실제 TSX 서버 렌더링 테스트로 인증 접근 제한, 사용자 정보, 온보딩 기존 프로필 인식, 레이블·접힌 추가 입력·지역 제한·옵션 상태를 확인한다.

이전 단계는 기존 101개와 신규 22개를 포함한 123개 테스트가 통과했다. 이번에는 지역 선택 전이·잘못된 계층·말단과 학교 선택 기준·UI 상태·기존 지역 보존·지역 외 실제 모의 PATCH 등을 15개 추가해 총 138개 테스트가 통과했다. `npm run lint`와 `npm run build`(strict TypeScript 검사 포함)도 통과했다. 테스트 프로세스 생성이 샌드박스에서 EPERM으로 차단되어 동일 명령을 승인된 제한 밖 실행으로 검증했다. SSR·선택 로직 단위 테스트는 실제 브라우저의 드롭다운 키보드·클릭·포커스 동작을 대신하지 않는다.

실제 브라우저의 입력·클릭·키보드·모바일 시각 표시·가로 스크롤, 카카오/쿠키와 인증된 서버 저장, 여러 탭·기기에서의 동시 수정은 미검증이다. 모바일 1열·터치 영역·필드 너비는 CSS와 마크업 검사로만 확인한다. 서버에 버전/ETag 기반 충돌 계약이 없으므로 다른 탭의 동시 전체 교체를 프런트엔드만으로 병합하지 않는다. 지역 데이터 제공, 실제 테스트 계정 저장 및 비동기 판정 완료 후 추천 갱신을 후속 통합 확인한다.
