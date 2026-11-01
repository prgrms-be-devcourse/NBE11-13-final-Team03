# 마이페이지·맞춤 조건·관심 분야

## 화면과 접근

`/mypage`는 인증 Context의 서버 확인 사용자 정보(닉네임·이메일·계정 유형), 프로필·관심 분야 등록 상태, 설정 진입, 기존 204 계약의 로그아웃을 제공한다. 기본 회원 정보 편집은 제공하지 않는다. `/mypage/profile`과 `/mypage/interests`는 각각 편집 화면이다. `/onboarding`은 맞춤 조건 → 선택적 관심 분야 → 완료·홈 이동 흐름이며 기존 프로필을 인식한다. 저장은 단계별로 실행하며 화면 이탈 시 이미 서버에 저장된 값을 삭제하거나 되돌리지 않는다. 관심 분야 단계에서 이전 단계로 돌아갈 수 있고, 저장 중에는 단계 이동을 막는다. 단계 이동으로 폼이 다시 열리면 서버에 저장한 값을 사용하며 저장하지 않은 초안은 보관하지 않는다. 프로필이 없는 동안 완료 화면으로 진행하지 않는다. 지역 선택이 불가능해도 별도의 관심 분야 설정 링크는 사용할 수 있다.

확인 중·인증 확인 실패·비로그인 상태를 구분하고, 인증된 경우에만 보호 Query와 폼을 마운트한다. 공개 홈·검색·상세는 기존대로 유지한다. 로그인 복귀 경로에는 실제 추가한 두 설정 경로만 허용한다.

## 확인한 API 계약

- `GET /api/users/me`: 기존 인증 Query의 id, nickname, email, role을 사용한다. 닉네임·이메일 편집 API는 없다. 최신 UserController에 회원 탈퇴 DELETE는 있으나 이번 구현 범위에서 제외한다.
- `GET /api/users/me/eligibility-profile`: 200은 현재 프로필, 404 **ELIGIBILITY_001만** 미등록으로 처리한다. 다른 404·401·403·네트워크·서버 오류는 미등록으로 바꾸지 않는다.
- `PUT /api/users/me/eligibility-profile`: 최초 등록·전체 교체이며 생략한 선택값은 null이다. 공개 지역 목록에서 유효한 거주지를 선택하면 신규 등록할 수 있다. 지역 조회 실패·빈 목록·미완성 선택에서는 등록을 허용하지 않는다.
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
- 화면은 대분류 한 줄 목록이며 한 번에 하나만 펼친다(`<details name>`). 접힌 줄에도 선택지 수와 실제 선택 개수(저장 키 기준)를 보여 주고, 위쪽 선택 요약에서 바로 해제할 수 있다. 펼침은 표시만 바꾸며 선택 상태와 PUT 본문은 그대로다. 옵션 응답에 공고 유형 정보가 없어 청년정책·직업훈련 탭으로 나누지 않으며, 복합 분류 문자열(`일자리,교육` 등)도 그대로 표시한다. 정규화된 API가 대분류별 유형을 제공하면 같은 목록을 유형별 묶음으로 나누면 된다.

## 지역 선택 구조와 연결 경계

병합된 로컬 [RegionController](../../backend/src/main/java/com/team3/backend/region/controller/RegionController.java), [RegionResponse](../../backend/src/main/java/com/team3/backend/region/dto/RegionResponse.java), [RegionsResponse](../../backend/src/main/java/com/team3/backend/region/dto/RegionsResponse.java), [RegionQueryService](../../backend/src/main/java/com/team3/backend/region/service/RegionQueryService.java)와 Controller·Service 테스트, SecurityConfig를 확인했다. `GET /api/regions`만 공개하며 `{ regions: [{ code, name, parentCode, leaf }] }` 객체로 전체 사전을 코드 오름차순 반환한다. 빈 사전은 `{ regions: [] }`다. 코드는 문자열이다.

[RegionDirectory](../../backend/src/main/java/com/team3/backend/region/directory/RegionDirectory.java)는 DB에서 색인을 만들며 조회 Service는 하위 지역 유무로 leaf를 계산한다. 사전에 없는 부모는 서버가 parentCode=null로 반환한다. 프로필 Service는 같은 색인에서 거주지에 하위가 없는지 검사하고 학교는 코드 존재 여부만 검사한다. 지역 코드는 10자리 문자열이며 앞 2자리는 시·도 구분에 사용한다. 시·도 요약값과 API의 전체 지역 코드를 혼동하지 않으며 프런트엔드는 반환된 전체 코드를 가공·보정하지 않는다.

외부 데이터를 받는 [RegionSelect](../src/pages/profile/RegionSelect.tsx)를 거주지·학교에 공통 사용한다. 컴포넌트 내부 HTTP 요청은 없다. source props로 unavailable/loading/error/ready 상태와 재시도 콜백을 전달하며, ready의 빈 목록과 잘못된 계층은 별도로 표시한다. 기본 HTML select·label·fieldset·legend와 오류 설명 연결, 전역 키보드 포커스 스타일을 사용한다. 기존 모바일 1열 그리드를 재사용한다.

- [regions.ts](../src/api/regions.ts)의 `getRegions`는 기존 apiClient로 `/regions`를 호출한다. 공개 요청이므로 requiresAuth를 지정하지 않으며 AbortSignal을 전달한다. 객체 래퍼와 행 필드 타입, 계층을 검사하고 오류를 빈 목록으로 바꾸지 않는다.
- `toRegionOptions(rows)`는 DTO를 UI의 value/label/parentValue/isLeaf로 변환한다. 코드·이름을 변경하거나 숫자로 변환하지 않는다. 중복·불명확한 행을 추측해 고치지 않는다.
- [regionSelection.ts](../src/pages/profile/regionSelection.ts)는 깊이를 2로 제한하지 않는다. 시·도 → 시·군·구 → 일반구의 3단계도 하위 목록이 있는 동안 선택란을 추가한다. 기존 말단 코드에서 모든 상위 경로를 복원한다. 중복 코드, 누락된 부모, 순환·자기 참조, 말단 부모의 자식, 자식 없는 비말단은 목록 전체를 선택 불가로 처리한다. 순환 검사는 재귀 없이 수행한다.
- 거주지는 isLeaf=true만 확정한다. 비말단 지역을 선택하거나 상위 선택을 변경하면 기존 하위 경로를 비우고 최종 값은 null로 둔다. 학교는 사전에 존재하는 시·도와 중간 시·군·구도 확정할 수 있으며, 하위 선택을 비우면 선택한 상위 지역을 사용한다. 최상위를 비우면 학교 소재지는 null이 된다.
- 세종시 3611000000이 최상위 leaf=true로 전달되면 추가 시·군·구 없이 확정한다. 코드별 예외 처리나 가짜 하위 행을 추가하지 않는다. 다른 최상위 말단 지역도 같은 규칙으로 동작한다.
- 기존 코드가 현재 목록에 없으면 코드와 유지 안내를 표시하고 다른 지역을 자동 선택하지 않는다. 로딩·오류·빈/잘못된 목록도 기존 값을 변경하지 않는다. 서버에서 읽은 **변경 없는 기존 코드**는 PATCH에서 생략해 지역 외 항목 수정을 허용한다. 단, 정상 목록에서 기존 거주지가 명확히 비말단으로 확인되면 저장을 차단한다. 신규·변경 코드는 정상 목록의 선택 기준을 통과해야 하며, 컴포넌트 표시와 별도로 제출 시에도 검증한다.

`regionsQuery()`는 공개 `['regions']` 키, staleTime 1시간·gcTime 24시간을 사용하며 창 포커스로 재조회하지 않는다. [useRegions](../src/pages/profile/useRegions.ts)는 동일 Query를 공유하고 loading/error/ready와 명시적 refetch를 RegionSource로 전달한다. 프로필 편집과 온보딩이 이 Query를 사용하며 관심 분야 전용 화면에서는 지역 요청을 시작하지 않는다. 두 폼 및 재진입의 fresh 캐시는 재사용하고 인증 전환 시에도 공개 지역 사전은 제거하지 않는다. 오류 상태에서는 이전 사전이 있어도 새 지역 선택을 허용하지 않는다.

신규 등록은 필수 입력과 거주지 말단 선택을 모두 통과한 뒤 기존 PUT으로 저장한다. 기존 프로필 수정은 PATCH를 유지해 바뀐 코드만 보내며, 변경하지 않은 지역은 생략한다. 서버 성공 후 프로필 Query와 개인화 조회를 갱신하고 온보딩은 관심 분야 단계로 이동한다. 이전 단계·건너뛰기·완료와 홈 링크, 저장 중 중복 제출·단계 이동 차단은 유지한다. fixture는 `tests/`에만 둔다.

## Query와 인증 격리

프로필은 `['profile', userId]`, 관심 분야는 `['user-interests', userId]`로 분리하고 gcTime=0으로 화면 이탈 후 민감 데이터를 불필요하게 보관하지 않는다. 저장 성공 시 해당 Query의 서버 응답을 반영하고 추천·검색·상세·홈 공개 목록만 invalidate한다. 전체 캐시를 삭제하지 않으며 공개 분류 옵션을 유지한다. 프로필 변경 후 판정 결과는 서버의 커밋 후 비동기 갱신이므로 즉시 최신 판정을 보장하지 않는다. 임의 타이머·폴링은 추가하지 않는다.

기존 인증 전환 정리에 두 사용자별 캐시를 포함한다. 저장 직전·응답 후 사용자 객체 동일성, 인증 확인/전환 진행 여부, AbortSignal을 검사한다. 화면 이탈 시 요청을 취소하고 이전 저장 응답을 새 사용자 캐시·온보딩 완료 상태로 반영하지 않는다. 서버에서 이미 끝난 저장은 취소로 되돌릴 수 없다. 응답 유실 후에는 재조회로 반영 여부를 확인해야 한다.

## 검증과 남은 범위

이전 마이페이지 구현 작업(2026-10-26)에서 `/api/opportunities?size=1`, `/api/interests/options`는 HTTP 200(분류 8개), 세 사용자 API(`/users/me`, `/users/me/eligibility-profile`, `/users/me/interests`)는 인증 쿠키 없이 모두 401이었다. DB나 실제 사용자 정보를 변경하지 않았다. 기존 개발 프록시는 기본 localhost:8080을 사용하며 설정을 변경하지 않았다.

실제 `getInterestOptions`·`searchOpportunities` 함수로도 백엔드 응답 검증을 통과했다(분류 8개·검색 1개). Node 실행에는 브라우저 출처가 없으므로 상대 `/api` 설정을 유지하고 HTTP 전송 adapter에서만 실제 upstream을 지정했다. baseURL을 절대 주소로 변경하는 시도는 기존 경로 보호 규칙에 의해 차단됐다. 이는 실제 브라우저·Vite 프록시 통합 확인을 대신하지 않는다.

Node 모의 API·QueryObserver·MutationObserver 테스트는 미등록/오류 구분, DTO·enum·조건부 범위 검증, 최초 PUT·차이 PATCH, 생략/null, 관심 분야 전체 교체·기존 NCS 보존·중복·30개 제한, CSRF/401/403/400/5xx, 선택적 Query 갱신, 계정 전환 및 지연 응답, 화면 이탈 취소를 검증한다. 실제 TSX 서버 렌더링 테스트로 인증 접근 제한, 사용자 정보, 온보딩 기존 프로필 인식, 레이블·접힌 추가 입력·지역 제한·옵션 상태를 확인한다.

이번 지역 API 연결 작업의 검증 결과는 다음과 같다.

- 실제 공개 GET `/api/regions`의 HTTP 200 및 `getRegions` 응답·계층 검증 결과는 유지한다. 담당자 확인에 따르면 현재 DB에는 개발용 임시 테스트 데이터 약 10건만 등록되어 있고, 전국 지역 데이터는 아직 적재하지 않았다. 일부 지역만 반환되는 것은 예상된 동작이며 데이터 동기화 실패나 API 구현 오류로 판단하지 않는다. 현재 테스트 데이터와 fixture로 확인한 API 응답·계층 선택·leaf 검증 결과는 유지한다.
- 기존 기록에서 `11`, `26`, `41`을 전체 지역 코드처럼 기재하고 사전 동기화 문제로 해석한 부분을 정정한다. 앞 2자리 시·도 요약과 실제 10자리 반환 코드는 구분해야 한다. 이번 정정으로 연동 코드나 검증 로직을 변경하지 않으며 새로운 API 호출·통합 검증 결과를 추가하지 않는다.
- 실제 인증 없는 프로필·관심 분야 GET은 401이다. 허용된 테스트 계정이 없어 실제 서버의 PUT/PATCH는 실행하지 않았다.
- Node API/Query 테스트는 래퍼·필드 타입·잘못된 계층, 빈 목록과 오류, 병렬 요청 공유·fresh 캐시, 명시적 재시도, 2·3단계·말단·학교 중간 지역·경로 복원·초기화를 검증한다.
- `profile-browser.test.mjs`는 격리된 Chrome과 테스트 전용 Vite 서버에서 실제 React·Query·apiClient·폼을 실행한다. HTTP adapter만 모의 처리해 3단계 선택·상위 변경·세종시·학교 중간 지역, 최초 PUT, 중복 제출 방지, 이전 단계 복귀·경로 복원, 관심 분야 저장·완료·홈 링크, 지역만 변경하는 PATCH와 지역 Query 1회 공유를 검증한다. 실제 인증된 백엔드 저장을 검증한 것은 아니다.
- `npm run lint`, `npm run build`(strict 타입 검사 포함)가 통과했다. 전체 테스트는 기존 141개와 신규 7개를 포함한 148개가 통과했으며 브라우저 테스트도 생략하지 않았다. 테스트용 Vite 캐시는 별도 임시 경로로 분리해 병렬 프록시 테스트·개발 서버와 공유하지 않는다.

사용자 브라우저의 수동 키보드·모바일 시각 확인, 실제 카카오·쿠키, 인증된 백엔드 저장과 여러 탭·기기의 동시 수정은 미검증이다. 서버에 버전/ETag 충돌 계약이 없어 다른 탭의 전체 교체를 프런트엔드만으로 병합하지 않는다. 추후 전국 지역 데이터가 적재되면 전체 지역 선택·세종시·3단계 경로를 확인하고, 허용된 로그인 테스트 계정에서 실제 프로필 PUT/PATCH 및 비동기 판정 완료 후 추천 갱신을 추가 검증한다. 이는 예정된 데이터 적재 이후의 후속 작업이며 현재 API 오류에 대한 복구 작업이 아니다.
