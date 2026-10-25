# 통합 검색

## 구현 범위

`/opportunities`에서 공개 검색, 유형·분류·모집 상태·유형별 날짜 필터, 정렬과 페이지 이동을 제공한다. 데스크톱은 좌측 필터와 우측 목록, 1024px 미만은 별도 필터 dialog를 사용한다. 기본 `<dialog>`의 포커스 제한·Escape 닫기, 닫은 뒤 필터 버튼 포커스 복귀와 배경 스크롤 잠금을 구성했다. 실제 브라우저 동작 검증은 아래 한계와 구분한다.

## 실제 계약과 데이터

- [검색 Controller](../../backend/src/main/java/com/team3/backend/search/controller/OpportunitySearchController.java)와 [Service](../../backend/src/main/java/com/team3/backend/search/service/OpportunitySearchService.java)의 최신 로컬 구현 및 관련 Controller 테스트를 확인했다. `GET /api/opportunities`는 `apiClient.get('/opportunities')`로 호출한다. 공개 요청이며 `requiresAuth`를 지정하지 않는다. 기존 인증·CSRF·재발급 코드는 변경하지 않았다.
- [목록 DTO](../../backend/src/main/java/com/team3/backend/search/dto/OpportunitySearchItemDto.java)의 유형·ID·제목·대/중/세부 분류·최초 등록일·nullable 신청 가능성·저장 여부만 표시한다. null 판정과 실제 `UNKNOWN` 판정을 구분하고 저장 여부는 읽기 전용으로 표시한다. 숨김 공고도 검색에 포함된다.
- [페이지 DTO](../../backend/src/main/java/com/team3/backend/search/dto/OpportunitySearchResponseDto.java)의 `page`, `size`, `totalElements`, `totalPages`, `hasNext`를 사용한다. API의 0-based 페이지를 화면에서 1-based 번호로 표시한다. 범위를 벗어난 빈 페이지에서는 첫 페이지로 돌아갈 수 있다.
- 제목은 부분 일치, 분류는 정확히 일치한다. 실제 공개 `GET /api/interests/options`와 [분류 DTO](../../backend/src/main/java/com/team3/backend/interest/dto/InterestOptionsResponse.java)의 계층을 TanStack Query로 조회해 종속 선택형 필터를 제공한다. 이 목록은 유형별로 구분되지 않은 통합 목록이다. 대분류 선택 시 해당 중분류만, 중분류 선택 시 해당 세부 분류만 표시한다. 상위 분류 변경 시 하위 선택을 초기화하고 하위 목록이 없으면 선택을 비활성화한다. 분류 문자열의 공백·특수문자는 변경하지 않고 URL과 요청에 그대로 전달한다.
- 분류 목록의 로딩·조회 실패·성공한 빈 목록을 구분한다. 조회 실패 시 선택을 비활성화하고 재시도 버튼을 제공하며 자유 입력이나 임의 목록으로 대체하지 않는다. URL에서 복원한 기존 조건은 유지한다. 옵션 조회 성공 후 해당 조건이 목록에 없거나 계층과 맞지 않으면 안내하고, 다시 선택하거나 초기화하기 전까지 필터 적용을 막는다.
- 모집 상태 enum은 `UPCOMING`, `OPEN`, `ALWAYS_OPEN`, `CLOSED`, `UNKNOWN`이다. `OPEN`은 청년정책에서는 모집 중, 직업훈련에서는 교육 시작일이 오늘 또는 이후인 개강 예정 검색이다. 실제 훈련 모집기간 확정으로 표시하지 않는다. 신청 가능성 배지와 모집 상태 필터를 혼용하지 않는다.
- 청년정책의 `deadlineFrom/To`는 신청 마감일 범위, 직업훈련의 `startDateFrom/To`는 교육 시작일 범위다. 양 끝은 포함한다. 날짜·전용 정렬은 해당 유형에서만 제공하고 유형 변경 시 호환되지 않는 값은 제거한다.
- `LATEST`, `OLDEST`는 최초 등록일 기준이다. `DEADLINE_PRIORITY`는 청년정책 전용이며 상태 우선순위는 OPEN → UPCOMING → ALWAYS_OPEN → CLOSED → UNKNOWN이다. `START_DATE_ASC`는 직업훈련 전용이다.

## URL·요청 정책

[searchParams.ts](../src/pages/opportunities/searchParams.ts)에서 URL 파싱·직렬화·검증을 함께 관리한다. 빈 값, 기본 정렬 LATEST, page=0, size=20은 URL과 API 파라미터에서 생략한다. size는 1~100이며 잘못된 enum·페이지·크기·날짜·유형 조합은 안전한 기본값으로 정리한다. 정규화한 URL은 현재 기록을 replace하고, 잘못된 조건 또는 미지원 인증·지역 필터를 제거한 경우 안내를 표시한다.

검색어는 검색 버튼이나 Enter로 실행할 때만 반영한다. 필터 적용과 정렬 변경은 page를 0으로 초기화하고, 페이지 이동은 적용된 조건을 유지한다. 새로고침과 뒤로/앞으로 이동은 URL에서 조건을 복원하도록 구성했다.

‘전체 초기화’는 검색어·필터·정렬·페이지를 함께 초기화한다. 필터 패널의 ‘필터 초기화’는 검색어와 페이지 크기는 유지하고 필터 초안과 정렬을 기본값으로 되돌린다. 실제 검색에는 ‘필터 적용’을 눌러 반영한다. 닫기는 미적용 초안을 버리고 기존 검색 조건을 유지한다.

[opportunities.ts](../src/api/opportunities.ts)는 요청·응답 타입을 사용하고 실제 응답 형태를 검사한다. TanStack Query의 검색 조건별 키와 AbortSignal로 이전 요청을 취소하며 늦은 결과가 현재 화면을 덮어쓰지 않게 한다. 최초 로딩, 조건 변경 로딩, 갱신, 빈 결과와 오류를 구분하고 오류를 0건으로 바꾸지 않는다. 재시도는 실제 refetch에 연결한다. 이전 결과를 보관하는 placeholder는 조건 변경 로딩 구분에만 사용하며 새 조건의 결과처럼 표시하지 않는다.

## 보류한 필터와 필요한 백엔드 지원

- 지역: [RegionDirectory](../../backend/src/main/java/com/team3/backend/region/directory/RegionDirectory.java)는 DB에 존재하는 코드로 유효성을 판단한다. 현재 공개 지역 옵션 API 또는 신뢰할 수 있는 고정 코드 목록이 없다. 지역명을 보고 코드를 추정하거나 임의 문자열을 전송하지 않는다. 화면에는 비활성 선택과 이유를 표시하고 URL의 `region`도 안내 후 제외한다. 후속 지역 검색을 위해 서버의 실제 유효 코드·이름·상위 코드 목록을 제공하는 공개 옵션 API가 필요하다.
- 신청 가능성 필터: 신뢰할 수 있는 인증 상태가 없어 비노출로 유지한다. URL의 `eligibilityStatuses`도 안내 후 제외한다. 응답에 실제 판정이 있는 경우에만 배지를 표시하며 이를 로그인 상태 판정에 사용하지 않는다.
- 목록의 모집 상태·신청 마감일·교육 시작일·지역명: 현재 목록 DTO에 없다. 등록일로 계산하거나 개별 상세 API를 다건 호출하지 않는다. 후속 카드에서 필요하면 해당 필드와 미확인 값의 의미를 목록 계약에 추가해야 한다.
- 제목은 [기회 상세 화면](detail.md)으로 연결하며 현재 검색 조건과 페이지를 이동 state에 담는다. 로그인, 저장·숨김 변경, 추천·행동 이벤트·알림은 검색 구현에 포함하지 않는다.

## 검증 범위

- Node 테스트로 URL·요청 직렬화와 복원, 잘못된 값, 날짜 범위와 유형 전환, 페이지 초기화·조건 유지, 공개 401 종료, 응답 검증, 분류 옵션과 요청 취소·늦은 응답 경합을 검증한다.
- 서버 렌더링 테스트는 실제 TSX의 목록·빈 결과·오류·로딩, 폼 label·날짜 오류 연결, dialog 속성과 페이지 번호를 확인한다. 테스트 데이터는 테스트 파일 내부에만 둔다.
- 분류 변경 시 하위 값 초기화, 부모별 선택 목록·선택 복원·세부 분류 비활성화, 옵션 로딩·오류·빈 목록, 원본 분류 문자열 보존을 검증한다. Vite 개발 프록시 테스트는 실제 연결 거부를 `502 / DEV_BACKEND_UNAVAILABLE`로 구분하고, 백엔드가 반환한 HTTP 오류는 그대로 전달하는지 확인한다. 브라우저 네트워크 오류만으로 백엔드 미실행을 단정하지 않는다. 이 진단 응답은 개발 프록시에만 적용되며 운영 프록시에는 별도 설정이 필요하다.
- 실제 `localhost:8080/api/opportunities?size=1` 요청은 시간 초과로 통합 결과를 확인하지 못했다. 백엔드·DB가 준비된 환경에서 정상·빈 결과·오류 응답을 추가 확인해야 한다.
- 이후 백엔드 `bootRun` 시도는 `application_user` 비밀번호 인증 실패로 종료됐다. 공개 지역 옵션 API 자체가 없는 것과 백엔드 기동 실패로 분류·검색 API에 연결할 수 없는 것은 별개의 문제다. DB 계정 설정은 이 프런트엔드 작업에서 변경하지 않는다.
- 브라우저 도구는 현재 URL을 확인하지 못해 중단됐다. PC·모바일 시각 표시, 가로 스크롤, 실제 dialog 적용·초기화·Escape·포커스 복귀 및 새로고침·뒤로/앞으로 이동은 브라우저에서 미검증이다. 서버 렌더링·모의 테스트 통과로 이를 완료했다고 간주하지 않는다.
