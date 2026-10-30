# 사용자 행동 이벤트

## 계약

로컬 UserBehaviorEventController·UserBehaviorEventService·UserBehaviorEventRequestDto와 enum(UserBehaviorEventType·UserBehaviorSource·ExternalLinkType), 관련 Controller·PostgreSQL 테스트를 확인했다. 백엔드는 수정하지 않았다.

- POST `/api/opportunities/{id}/events`, 본문 `{ eventType, source, linkType? }`. 인증·CSRF가 필요하며 성공은 204다.
- 화면이 보내는 종류는 IMPRESSION·DETAIL_VIEW·EXTERNAL_LINK_CLICK뿐이다. SAVE/HIDE는 서버가 저장·숨김 상태가 실제로 바뀔 때 직접 기록하므로 보내지 않는다([저장·숨김 문서](preferences.md)).
- IMPRESSION은 RECOMMENDATION 출처만 허용된다. linkType은 EXTERNAL_LINK_CLICK에만 보내며 값은 APPLICATION·DETAIL·REFERENCE다.
- 서버는 반복 기록을 허용하고 중복을 제거하지 않는다. 한 번의 행동이 한 건이 되도록 화면에서 보낼 시점을 정한다.

## 전송 (`src/api/behaviorEvents.ts`)

- 기존 apiClient의 쿠키·CSRF·`requiresAuth`를 사용한다. 만료 토큰의 재발급 후 1회 재전송 외에 자동 재시도는 하지 않는다.
- 보내기 직전에 인증 Query의 확인된 사용자가 행동한 사용자와 같고 로그인·로그아웃·탈퇴 전환이 진행 중이 아닐 때만 보낸다. 비로그인·확인 중·오류 상태에서는 보내지 않는다.
- 모든 실패(400·401·403·404·5xx·네트워크·시간 초과)를 삼킨다. 화면 표시·페이지 이동·새 탭 열기는 기록 결과를 기다리지 않는다.

## 이벤트별 시점

| 이벤트 | 위치 | 기준 |
|---|---|---|
| IMPRESSION | `pages/home/RecommendationsSection.tsx`, `recommendationImpression.ts` | 홈에 실제 렌더링된 추천 카드(최대 3개, `li`)만 관찰한다. 50% 이상이 1초 연속 보이면 한 번 보낸다. 비율이 내려가거나 탭이 숨겨지면 다시 센다. API가 준 나머지 추천은 렌더링하지 않으므로 기록하지 않는다. |
| DETAIL_VIEW | `pages/opportunities/OpportunityDetailPage.tsx` | 상세 조회가 성공해 내용을 표시할 때 한 번 보낸다. 조회 실패·404·잘못된 ID에서는 보내지 않는다. |
| EXTERNAL_LINK_CLICK | 같은 파일의 공식 링크 | 정책 신청 페이지=APPLICATION, 원문 공고=DETAIL, 참고 자료=REFERENCE. 같은 URL이 겹치면 화면에 남는 첫 링크의 유형이다. 왼쪽 클릭(키보드 Enter 포함)과 가운데 버튼 클릭마다 한 건이며 기본 링크 동작을 막지 않는다. |

## 중복 방지와 진입 단위

- 노출: 홈 화면(RecommendationsSection)이 마운트된 동안 `사용자:공고`별 한 번이다. 재렌더링·추천 재조회·저장 상태 갱신·StrictMode의 effect 재실행은 같은 진입이다. 다른 화면에서 돌아오거나 새로고침하면 새 진입이다. 숨김 등으로 새로 렌더링된 카드는 그 진입에서 처음 보일 때 한 번 기록한다.
- 상세: `사용자:location.key:공고` 단위로 한 번이다. 상세 재조회·재렌더링은 같은 진입이고, 다른 화면으로 나갔다가 다시 들어오거나 새로고침하면 새 진입이다.
- 전역 상태나 새 라이브러리를 추가하지 않았다. 컴포넌트의 ref만 사용한다.

## 출처(source)

앱 내부 링크가 React Router `location.state.behaviorSource`에 남긴 값만 쓴다. URL 쿼리는 읽지 않으며, 허용 값(RECOMMENDATION·SEARCH·SAVED·OTHER)이 아니면 OTHER다.

| 진입 | 출처 |
|---|---|
| 홈 추천 카드 → 상세 | RECOMMENDATION |
| 검색 목록 → 상세 (기존 `searchReturn`과 함께 전달) | SEARCH |
| 저장 목록 → 상세 | SAVED |
| 공개 홈 카드, 알림, 직접 주소 입력 | OTHER |

상세의 DETAIL_VIEW·EXTERNAL_LINK_CLICK과 상세 저장 버튼의 SAVE 출처가 같은 값을 쓴다. 새로고침·뒤로 가기로 다시 연 상세는 브라우저 기록에 남은 state의 출처를 그대로 쓰며 새 진입으로 기록한다.

## 검증과 한계

- `tests/behavior-events.test.mjs`: 경로·메서드·본문·인증·CSRF, 비로그인·다른 사용자·인증 전환·잘못된 ID 미전송, 오류별 1회 요청 후 조용한 종료, 재발급 실패 후 중단, 출처 검증, 50%·1초 연속 판정, 탭 숨김·정리 시 취소.
- `tests/behavior-events-browser.test.mjs`: 실제 Chrome·StrictMode·AppRoutes·Query에서 렌더링된 카드만 관찰, 미노출·50% 미만·1초 미만 미전송, 재조회·저장 갱신 후 중복 없음, 홈 재진입 재기록, 상세 재조회 중복 없음, 진입별 출처와 상세 저장 출처, 링크 유형·재클릭·실패 시 새 탭 기본 동작 유지, 비로그인 확인 후 미전송을 확인한다. 로그아웃·로그인 전환 중 미전송은 단위 테스트가 확인한다. HTTP adapter는 모의 처리다.
- 헤드리스 Chrome의 가상 시간에서는 실제 IntersectionObserver 알림이 렌더링 프레임에 따라 불규칙해 브라우저 테스트는 관찰자만 대역으로 바꾸고 표시 비율을 직접 알린다. 실제 스크롤 기하 판정(카드 크기·뷰포트별 50%)은 수동 확인 대상이다.
- 우클릭 메뉴의 "새 탭에서 열기"·링크 복사는 브라우저가 클릭 이벤트를 주지 않아 기록되지 않는다.
- 실제 백엔드·DB 저장, 인증 쿠키·토큰 회전과의 통합은 실행하지 않았다.
