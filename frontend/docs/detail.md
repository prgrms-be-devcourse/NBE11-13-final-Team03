# 기회 상세

## 범위와 백엔드 계약

`/opportunities/:id`는 [Controller](../../backend/src/main/java/com/team3/backend/search/controller/OpportunityDetailController.java), [Service](../../backend/src/main/java/com/team3/backend/search/service/OpportunityDetailService.java), [DTO](../../backend/src/main/java/com/team3/backend/search/dto/OpportunityDetailResponseDto.java), 관련 enum과 상세 조회 테스트의 최신 로컬 구현을 기준으로 구성했다.

- `GET /api/opportunities/{opportunityId}`를 기존 `apiClient.get('/opportunities/{id}')`로 요청한다. 공개 조회이며 `requiresAuth`를 지정하지 않는다. 인증·CSRF·재발급 코드는 변경하지 않는다.
- 공고 또는 현재 정규화 버전이 없으면 404 `OPPORTUNITY_001`이다. 사용자별 판정은 저장된 최신 결과만 조회하며 상세 조회 중 새로 판정하지 않는다.
- 응답의 필수 구조를 검사한다. nullable 필드는 null과 JSON에서 생략된 경우를 허용하며, 유형별 상세가 없으면 공통 정보와 누락 안내를 표시한다. 필수 구조나 공고 ID가 요청과 다르면 오류로 처리한다.
- ID는 양의 정수 문자열이며 JavaScript의 안전한 정수 범위 이내만 허용한다. 잘못된 ID는 요청하지 않는다. 백엔드 Long 전체 범위의 ID가 필요해지면 정밀도를 보존하는 문자열 응답 계약을 검토해야 한다.
- TanStack Query 키는 `['opportunity-detail', id]`이다. 기존 QueryClient 정책을 따르며 AbortSignal을 전달한다. ID 변경 시 이전 응답을 새 공고처럼 표시하지 않는다.

## 표시 정책

### 모집 상태와 신청기간

상세 API의 `applicationStatus`를 그대로 한글로 표시한다. 상세 서비스는 `VALID` 신청기간만으로 모집 상태를 계산한다. 직업훈련 교육 시작일을 사용하는 검색 `OPEN` 규칙과 다르며, 화면에서 상태를 다시 계산하지 않는다.

모든 `applicationWindows`를 표시한다. `VALID`인 `FIXED`는 유효한 날짜 범위와 누락된 시작·종료일을 구분하고, `ALWAYS`는 상시 신청, `CLOSED`는 신청 마감으로 표시한다. `UNKNOWN`, 유효하지 않은 날짜·범위, 검증되지 않은 항목은 확인 필요로 표시한다. `periodText`는 원문 기간 안내로 구분한다.

정책 사업기간과 훈련 교육기간은 신청기간과 별도 항목으로 표시한다.

### 지역과 지원 조건

[지역 Mapper](../../backend/src/main/java/com/team3/backend/search/service/OpportunityRegionItemMapper.java)의 `role`, `status`, `validationStatus`를 함께 사용한다. 검증된 `NATIONAL`만 전국으로 표시하고, 검증된 `SPECIFIC`만 응답의 지역명으로 표시한다. 거주지·학교·사업장 조건과 시행·교육 지역을 분리한다. 이름 누락·미검증·빈 목록은 지역 확인 필요로 표시하며 코드로 이름을 추정하지 않는다.

조건의 필수·우대·제외 성격을 구분한다. `VALID` 조건에서 확인된 연령 범위·출생일 경계, 코드표의 취업·학력·혼인·전공·특화 대상 값 등을 읽기 쉬운 표현으로 표시한다. `VALID`인 `NO_LIMIT`만 해당 조건 제한 없음으로 표시한다. 복합 조건이나 미지원 값·미검증 조건은 확정하지 않으며 `evidenceText`를 원문 확인 영역에 표시한다. 조건이 비었다고 제한이 없다고 판단하지 않는다.

소득은 검증 상태, 범위 상태, 단위 확인, 경계 포함 여부를 확인한다. 금액은 기간(월/연)과 대상(본인/가구)까지 확인된 경우에만 원화 금액으로 표시한다. 기준중위소득 비율과 학자금 지원구간은 금액과 분리한다. 불명확한 값은 원문 확인으로 남기며 내부 필드명·미지원 enum은 그대로 노출하지 않는다.

### 유형별 내용

- 청년정책: 소개·지원 내용·지원 규모, 신청 방법·선정 방법·서류, 주관·운영 기관, 사업기간, 기타 안내를 표시한다. 긴 텍스트의 줄바꿈은 유지한다.
- 직업훈련: 교육기관·교육기간·장소·대상, 비용·정원·연락처를 표시한다. [원본 입력 DTO](../../data-service/src/main/java/com/team3/dataservice/opportunity/normalization/dto/TrainingCourseRaw.java), 정규화 코드와 엔티티에서 `realMan → actualTrainingCost`는 실제 훈련비, `courseMan → courseFee`는 수강비로 확인했다. 응답에 비용 단위·지원 범위·본인 부담액을 확정하는 메타데이터는 없다. 따라서 원본 항목명과 숫자만 표시하고 무료·본인 부담금으로 해석하지 않는다. 0도 누락과 구분한다.

### 개인별 판정과 링크

`eligibility`가 없으면 현재 제공된 판정 없음으로 표시한다. 비로그인·미설정·만료 등 원인을 추측하거나 실제 `UNKNOWN`과 합치지 않는다. 판정이 있으면 기존 StatusBadge와 백엔드의 전체·조건별 사유를 표시한다. 모집 상태와 별도 영역으로 제공한다. `saved`는 읽기 전용이며 저장·해제 버튼을 만들지 않는다.

링크는 실제 제공된 절대 HTTP(S) URL만 허용한다. 위험한 스킴·상대 주소·자격증명·제어문자가 포함된 URL은 링크로 만들지 않는다. 원문·신청·참고 링크의 목적을 구분하고 새 탭 링크에 `noopener noreferrer`와 새 탭 안내를 제공한다. URL 형식 검사는 해당 사이트의 실제 운영 여부나 공식 도메인 소유권을 검증하지 않는다. 본문·근거 문장은 HTML로 실행하지 않고 React 텍스트로 표시한다.

## 검색 복귀와 접근성

검색 결과 Link는 현재 검색 URL을 이동 state에 담는다. 상세의 목록 복귀 링크는 검증·정규화한 검색 조건과 페이지를 유지한다. 직접 접속·state가 없거나 허용되지 않은 목적지인 경우 `/opportunities`를 사용한다. 브라우저 뒤로 가기에 의존하지 않는다.

데스크톱은 본문과 보조 정보의 두 열, 1024px 미만은 한 열이다. 기존 토큰·카드·포커스 스타일을 재사용하고 긴 텍스트 줄바꿈, 제목 계층, 로딩 status·오류 alert, 충분한 링크 영역을 구성했다.

## 검증과 후속 확인

- Node 모의 테스트: ID·요청 경로·Query Key·공개 401 종료, 두 유형 DTO와 누락 필드, 기간·지역·조건·소득 표시, 안전한 URL, 검색 복귀, 요청 취소·늦은 응답을 확인한다.
- 실제 TSX 서버 렌더링: 여러 신청기간과 사업·교육기간 분리, null 판정과 UNKNOWN, 원문 근거, 읽기 전용 저장 상태, 404·네트워크·시간 초과·5xx, 링크 state와 기본 접근성 구조를 확인한다. 테스트 데이터는 `tests/`에만 둔다.
- 실제 백엔드·DB와 브라우저 통합은 이번 작업에서 실행하지 않는다. PC·모바일 시각 표시, 가로 스크롤, 키보드 탐색, 새 탭 연결, 실제 목록 복귀와 새로고침은 후속 통합 검증 대상이다.
- 로그인·로그아웃·확인된 사용자 전환은 [인증 상태 소유자](auth.md)가 개인화된 상세·검색 캐시를 초기화·재조회한다. 후속 저장 상태 변경에서도 갱신을 연결해야 한다. 상세 판정만으로 로그인 상태를 판단하지 않는다.
- 비용 단위와 지원·본인 부담 의미를 확정해 표시하려면 신뢰할 수 있는 원본 명세 또는 백엔드 메타데이터 계약이 필요하다. 미지원 복합 조건·추가 코드값은 계약 확인 후 표시를 확장한다.
- 상세 UI는 프로필 수정, 저장·해제 API, 알림·행동 이벤트를 포함하지 않는다. 로그인과 홈 추천 연결은 별도의 [인증 흐름](auth.md)에서 제공한다.
