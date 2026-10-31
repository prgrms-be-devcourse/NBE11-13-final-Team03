# 관리자 화면 (`/admin`)

## 접근 제어

- 판별 기준은 `GET /api/users/me`의 `role`이다. 백엔드가 로그인 시 `app.admin-kakao-ids`로 부여한다. 사용자 ID·이메일·프런트 플래그로 추측하지 않는다.
- [AdminLayout](../src/pages/admin/AdminLayout.tsx)은 인증 확인 중·오류·비로그인·`USER`에서 관리자 콘텐츠를 렌더링하지 않는다. 하위 화면은 `role=ADMIN` 확인 뒤 `Outlet`으로만 그린다.
  - 비로그인은 로그인 안내를 보여 주고, 로그인 후 같은 경로로 돌아온다.
  - `USER`는 "접근 권한이 없어요"만 보여 준다.
  - 헤더의 `관리자` 메뉴도 확인된 `ADMIN`에게만 보인다.
- 최종 기준은 서버 응답이다. 관리자 API가 `403 COLLECTION_007`·`RETENTION_001`을 주면 관리자 화면 전체를 닫고 인증 정보를 다시 확인한다.
  - `/users/me`의 role은 DB 값이고 API 권한은 토큰 값이라, 권한이 바뀐 직후 두 값이 다를 수 있다.
- `403 RETENTION_002`는 권한 오류가 아니다. 서버 설정(`admin-execute-enabled`)으로 실행이 꺼진 상태로 보여 준다.
- 401은 기존 재발급 규칙(`requiresAuth`)을 따른다. 확정 실패하면 재로그인을 안내한다.
- 관리자 Query 키는 `['admin', userId, …]`다. 로그인 전환 시 `resetPersonalizedQueries`가 모두 지운다.

## 기능과 API

| 화면 | API | 구분 |
|---|---|---|
| 수집 이력 | GET `/api/admin/collection/syncs?date=` · GET `/syncs/{jobId}` | 조회. 목록은 대기·실행 중인 작업이 있을 때만 10초마다 재조회, 상세는 5초. 상세가 종료를 먼저 보면 목록을 한 번 갱신한다 |
| 수집 시작 | POST `/api/admin/collection/syncs/{YOUTH_POLICY\|EMPLOYMENT24}?pageSize&maxPages` | 실행: 입력 → 확인 단계 → 202 확인 |
| 실패 작업 재시도 | POST `/api/admin/collection/syncs/{jobId}/retry` | 실행: `FAILED`·`PARTIAL_SUCCESS`·`INTERRUPTED`에만 버튼 표시, 확인 단계 |
| 원본 레코드 | GET `/api/admin/collection/raw-records/{id}` | 조회. 저장(POST)은 제공하지 않는다 |
| 행동 로그 보관 미리보기 | GET `/api/admin/behavior-events/retention/preview` | 조회 (DB 변경 없음) |
| 행동 로그 보관 실행 | POST `/api/admin/behavior-events/retention/execute` `{ planToken }` | 실행 |

보관 실행 버튼은 다음 조건을 모두 만족할 때만 활성화된다.

- 미리보기 `status=READY`이고 `planToken`이 있다.
- `adminExecuteEnabled=true`다.
- 삭제·생성 대상이 있다.
- 삭제 예상 행 수·크기와 "복구할 수 없음"을 체크해 확인했다.

미리보기 상태는 화면에 한국어 이름과 설명으로 보여 준다.
- 설명은 백엔드 판정 조건을 그대로 옮긴다.
- 원래 API 코드도 `상태 코드`로 함께 표시하며, 응답 값은 바꾸지 않는다.
- 알 수 없는 코드는 원문만 보여 준다.
- 건너뜀 상태에서는 서버가 대상을 계산하지 않아 빈 목록을 준다. 이때 표 대신 "계산하지 않아요"라고 안내해 '대상 없음'과 구분한다.

확인 체크는 `planToken`이 바뀌거나 실행이 실패하면 초기화된다.

**결과 불확실 처리**

서버는 파티션별로 따로 커밋하므로, 응답을 못 받아도 일부가 이미 반영됐을 수 있다.
- 실행 요청만 60초 제한을 쓴다(`retentionExecuteTimeoutMs`). 공통 요청은 10초다.
  - 배포 경로의 CloudFront 원본 응답 대기(기본 30초)보다 길게 둔다.
  - 프록시 504도 아래처럼 처리한다.
- 다음 경우를 '결과 불확실'로 본다(`isUncertainExecution`): 시간 초과·네트워크 오류·5xx·응답 형식 불명.
- 결과 불확실이면 이렇게 동작한다.
  - 전용 안내를 보여 준다: 일부 정리가 이미 진행됐을 수 있음, 확인 순서, 서버 로그 `BEHAVIOR_RETENTION_EXECUTE`.
  - 미리보기를 무효화하고 다시 불러온다.
  - 실패 시점의 미리보기 갱신 횟수(`dataUpdateCount`)보다 새 미리보기를 성공적으로 받기 전까지 실행을 막는다(`retentionExecutionLocked`).
  - 미리보기를 다시 받는 중이거나 조회에 실패한 동안에도 막는다.
- 이전 실행이 아직 끝나지 않았는지는 미리보기로 알 수 없다. 서버의 advisory lock(`RETENTION_004`)과 planToken 재검증(`003`)을 믿는다.
- 4xx는 서버가 확정한 거부 응답이다. 계획 변경(`RETENTION_003`)·진행 중(`004`)·실행 불가(`005`)·비활성(`002`)은 각 안내를 보여 주고, 미리보기를 자동으로 다시 불러온다.

실행 작업은 같은 관리자 계정에서 한 번에 하나만 진행한다(공통 mutation key와 ref 가드). 자동 재시도는 하지 않고, 요청 전후로 같은 관리자 세션인지 확인한다.

## 디자인

[Admin.module.css](../src/pages/admin/Admin.module.css)의 `.admin` 안에서만 관리자 전용 변수를 정의한다(웜 그레이·오프화이트·차콜, 낮은 채도의 세이지). 사용자 화면의 전역 토큰은 바꾸지 않는다.

- 조회 영역: 그림자 없는 얇은 테두리 카드
- 실행 영역: 위쪽 선과 확인 단계로 구분한다. 삭제가 따르는 보관 실행만 붉은 계열을 쓴다.
- `수집 시작`: 섹션 제목 줄 오른쪽에 둔 보조 버튼이다. 수집 이력 카드의 조회 도구(날짜·새로고침)와 분리한다.
- 페이지 배경: 관리자 화면이 렌더링된 동안에만 `body:has(.admin)`으로 `#eeefea`를 쓴다. 일반 페이지 배경은 그대로다.
- 상태: 배경 배지 없이 글자색으로만 구분한다.
- 모바일: 왼쪽 메뉴가 위쪽 가로 줄로 바뀌고, 표는 표 영역 안에서만 가로로 스크롤한다.

## 검증과 한계

- `tests/admin.test.mjs`: 경로·메서드·`requiresAuth`·CSRF·202 확인, DTO 검증, 403 코드 구분, 세션 확인, 캐시 정리, 복귀 경로를 확인한다.
- `tests/admin-ui.test.mjs`: 인증 상태별 비노출, 헤더 메뉴, 재시도·보관 실행 노출 조건, payload 이스케이프, 관리자 스타일 범위를 확인한다.
- `tests/admin-browser.test.mjs`: 실제 Chrome에서 모의 HTTP로 다음을 확인한다.
  - 보관 실행의 시간 초과·500 → 결과 불확실 안내, 확인 체크 초기화
  - 미리보기 재조회 실패 동안 실행 차단, 새 미리보기 뒤에만 확인 가능
  - 409 → 미리보기 자동 갱신, 미리보기 변경 시 체크 초기화, 자동 재전송 없음
  - 수집 이력 폴링은 실행 중일 때만 동작하고, 완료 응답 뒤 멈춤
  - 이 테스트는 `scripts/test-policy.json`의 필수 브라우저 목록에는 없다(frontend 밖이라 등록하지 않았다).
- 실제 관리자 계정·백엔드·data-service와의 통합, 실제 수집 실행과 보관 정리 실행은 하지 않았다. 운영 데이터에 영향을 주므로 테스트 목적으로 호출하지 않는다.
