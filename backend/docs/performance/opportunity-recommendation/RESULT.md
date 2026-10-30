# 개인화 추천 V1: 전체 후보 순회 제거 (그룹별 상위 후보 DB 선정)

대상: `GET /api/users/me/recommendations`. 추천 정책(그룹·정렬·혼합 규칙)과 응답 형식은 바꾸지 않았다.
측정 도구는 저장소에 포함한다(`backend/src/test/java/com/team3/backend/recommendation/benchmark/`, 아래 "재측정").
측정 원자료와 전용 DB 환경 정의는 작성자 로컬에만 있다(`perf-data/`는 `.gitignore` 대상): `perf-data/opportunity-recommendation/` —
`env/`(전용 DB compose·복원·스키마 로그), `before/`(2026-10-29 Before), `comparison/`(1차 개선), `a-plan/`(타당성 검증),
`a-plan-after/`(이 문서의 After 측정·동등성·실행 계획). 이 문서의 수치는 모두 이 원자료에서 옮겼다.

## 요약

- 같은 날(2026-10-30) 개선 전 코드(HEAD 6ae6745)와 번갈아 측정한 API 평균 응답: **9.8~12.4초 → 0.02~1.04초 (11~17배, 최신 판정 없는 사용자 457배)**.
- 요청당 SQL **3,951~4,933회 → 4~11회**, 요청 스레드 할당 메모리 **783~962MB → 1MB 미만**, 커넥션 점유 9.8~12.4초 → 0.02~1.04초.
- 남은 시간의 대부분(관심 미설정 사용자 1.03초 중 0.99초)은 그룹별 상위 후보 SQL 1회다. 이 SQL은 추천 가능 후보 수에 비례해 DB 안에서 처리한다 (최대 약 9.7만 행 정렬, 디스크 21MB).
- 기존 결과와의 동등성: 무작위 Fixture 240건 × (H2, PostgreSQL) 불일치 0, 실제 덤프 합성 사용자 9명 선정 결과·HTTP 응답 본문 동일.

## 1. 변경 파일과 이유

| 파일 | 변경 |
|---|---|
| `recommendation/domain/repository/RecommendationCandidateRepository.java` | `findTopPerGroup`: 그룹별 상위 후보 네이티브 SQL 1회 (Hibernate 네이티브 쿼리라 SQL 통계·로그·테스트 계측에 그대로 잡힌다). 기존 `findAfter`는 참조 구현·측정용으로 유지 |
| `recommendation/service/RecommendationSelectionService.java` | 전체 순회 제거. DB 상위 후보 → 기존 매핑 → 기존 우선순위·혼합 규칙. 그룹 유지·혼합을 `TopCandidates`(패키지 전용)로 묶어 운영과 참조 구현이 같이 쓴다. `select(userId, today)`로 시그니처 단순화 |
| `recommendation/service/RecommendationCandidateService.java` | 후보 행 → `RecommendationCandidate` 매핑을 `toCandidates`로 분리해 운영(상위 후보 ≤30행)과 커서 순회가 공유 |
| `recommendation/service/RecommendationInterestMatcher.java` | SQL 조건용 `unrestricted()`·`codes(type)` 접근자 |
| `recommendation/service/RecommendationService.java` | 배치 크기 상수 제거, `select(userId, today)` 호출 |
| `preference/...` (1차 개선, 유지) | 숨김 ID 전체 1회 조회. 운영 추천은 이제 SQL `NOT EXISTS`로 숨김을 제외하고, 이 메서드는 참조 구현이 쓴다 |

테스트:

| 파일 | 내용 |
|---|---|
| `RecommendationFullScanReference` (신규, test) | 기존 전체 순회 구현을 그대로 옮긴 참조 구현. 그룹·혼합은 운영 `TopCandidates` 재사용 |
| `RecommendationSelectionEquivalenceTest` / `...PostgresTest` (신규) | 무작위 Fixture 40시드 × 사용자 6명: 운영 `select()` vs 참조 구현, `RecommendationEligibleCandidate` 전체 동일 단언. 대상 경우(0·1·5건, 관심 미설정, B·C 혼합, D 그룹, 정책·훈련 혼합, ELIGIBLE·POSSIBLY 혼합)가 실제로 나왔는지도 단언. H2·PostgreSQL 각각 |
| `RecommendationSelectionServiceTopPerGroupTest` (신규) | 운영 경로 단위: DB 행 순서와 무관한 재정렬·혼합, 관심 코드·그룹 한도 전달, 프로필 없음 시 조회 전 실패, 0건 |
| `RecommendationSelectionServiceTest` | 기존 규칙 테스트 13건의 단언 유지 + 1차 개선에서 추가한 숨김 1회 조회 테스트 1건. 대상만 참조 구현으로 (규칙은 운영 `TopCandidates`를 거친다) |
| `RecommendationPostgresIntegrationTest` | 배치 크기 비교는 참조 구현으로, 운영 결과가 같다는 단언 추가. 읽기 전용 단언을 "SELECT 또는 쓰기 없는 CTE"로 확장 |
| `RecommendationServiceTest` | 새 `select` 시그니처 |

## 2. 실행 구조 비교

| | 기존 (전체 순회) | A안 |
|---|---|---|
| 후보 탐색 | ID 커서 100건씩 987~1,029배치, 배치마다 후보·신청기간·훈련 일정·(숨김)·최신 판정 SQL | 최신 판정(ELIGIBLE·POSSIBLY) → 조건 묶음 → 현재 버전 → 후보 조건 → 숨김 제외 → 관심 적합도 → 그룹별 `row_number() ≤ 5`, SQL 1회 |
| Java로 오는 행 | 후보 약 10만 + 신청기간·훈련 일정 각 약 10만 행 | 0~11행 (+ 그 행의 신청기간·훈련 일정) |
| 그룹·정렬·혼합 | Java | DB가 그룹별 상위 5건을 고르고, Java가 기존 비교기로 다시 정렬한 뒤 기존 혼합 규칙 적용 |
| 응답 조립 | 저장 여부·지역·추천 이유 | 동일 코드 |

그룹별 5건 제한 근거: 기존 코드도 그룹마다 우선순위 상위 5건만 `PriorityQueue`에 남기고(초과 시 poll) 혼합 규칙은 그 5건 안에서만 고른다. 비교기는 ID까지 포함한 전순서다.

## 3. SQL 횟수와 API 응답 시간 (기준일 2026-10-30, 교차 측정)

조건: 전용 PostgreSQL 16.14(`yon-reco-perf`), 정규화 덤프 공고 140,657건(기준일 추천 후보 98,668건), 합성 사용자 S1~S5(실제 판정 엔진으로 생성).
MockMvc HTTP 경로(보안 필터 포함). 라운드 1·3은 V0→After, 라운드 2는 After→V0. V0는 라운드마다 워밍업 1 + 측정 5(n=15), After는 워밍업 2 + 측정 20(n=60).

| 시나리오 | V0 평균 ms | After 평균 ms | 배수 | V0 p95 | After p50 / p95 / p99 | V0 SQL | After SQL |
|---|---:|---:|---:|---:|---|---:|---:|
| S1 관심 미설정 | 12,400.6 | 1,033.2 | 12.0x | 16,177.9 | 976.3 / 1,383.4 / 2,025.5 | 4,933 | 11 |
| S2 대중적 관심 | 10,890.8 | 720.6 | 15.1x | 17,113.3 | 656.7 / 1,067.2 / 1,708.7 | 4,164 | 11 |
| S3 드문 관심 | 9,829.6 | 567.9 | 17.3x | 15,008.2 | 530.3 / 762.1 / 1,157.3 | 3,951 | 11 |
| S4 최신 판정 없음 | 11,250.6 | 24.6 | 457x | 12,503.2 | 20.0 / 36.6 / 168.6 | 4,928 | 4 |
| S5 숨김 2만 건 | 11,818.9 | 1,040.5 | 11.4x | 14,812.5 | 983.9 / 1,417.5 / 2,293.7 | 4,933 | 11 |

V0의 n=15에서 p99는 최대값이다. 라운드별 평균은 V0 8.7~13.5초, After S1 0.89~1.12초로 라운드 간 편차가 있으나 범위가 겹치지 않는다.
2026-10-29 Before(`perf-data/.../before/`, 평균 8.1~9.8초)와는 기준일·후보 수가 달라 직접 비교하지 않는다.
After SQL 11회 = 프로필 1 + 관심(사용자 확인·목록) 2 + 상위 후보 1 + 신청기간 1 + 훈련 일정 1 + 저장 여부 1 + 지역 4. 선정 0건이면 4회.

## 4. JDBC 시간과 커넥션 점유 (요청 평균 ms)

| 시나리오 | V0 JDBC | After JDBC | 그중 상위 후보 SQL | V0 커넥션 점유 | After 커넥션 점유 | 할당 MB V0 → After |
|---|---:|---:|---:|---:|---:|---|
| S1 | 9,491.3 | 1,012.8 | 986.6 | 12,394.4 | 1,028.3 | 961.6 → 0.9 |
| S2 | 8,385.6 | 701.9 | 678.0 | 10,884.5 | 715.8 | 821.0 → 0.7 |
| S3 | 7,571.1 | 551.5 | 529.4 | 9,823.8 | 563.7 | 783.4 → 0.4 |
| S4 | 8,681.2 | 13.8 | 8.2 | 11,245.2 | 20.5 | 914.3 → 0.2 |
| S5 | 9,070.9 | 1,021.0 | 993.2 | 11,813.3 | 1,035.7 | 931.9 → 0.7 |

After 응답에서 상위 후보 SQL 1회가 S4를 제외하고 93~96%다. 나머지 SQL·Java 처리·HTTP는 합계 약 16~47ms (응답 평균 − 상위 후보 SQL 평균).

## 5. 실행 계획과 예상 밖 결과 (`a-plan-after/explain/`, 운영 SQL 문장·바인드 값 그대로 PREPARE)

| 시나리오 | DB 정렬 행(=추천 가능 후보) | Java 반환 행 | custom 계획 실행 ms | generic 계획 실행 ms | 정렬 |
|---|---:|---:|---|---|---|
| S1 | 97,455 | 10 | 805~923 | 1,852~2,684 | external merge 21MB |
| S2 | 20,230 | 11 | 521~636 | 1,730~2,412 | external merge 4.4MB |
| S3 | 1 | 1 | 499~550 | 2,004~2,584 | 메모리 |
| S5 | 77,960 | 7 | 888~1,203 | 2,179~2,723 | external merge 17MB |

- 공고 관련 버퍼 접근이 약 55만~85만 블록(대부분 shared hit)으로 사용자와 무관하게 크다. 판정 결과 → 조건 묶음 → 현재 버전(약 13.7만) → 공고·훈련 상세를 Nested Loop로 거친 뒤 관심 필터를 적용하기 때문이다. 그래서 드문 관심(S3, 후보 1건)도 약 0.5초다.
- 플래너 추정 행 수(약 2천)가 실제(약 10만)와 크게 다르다 (훈련 조건 묶음 3개에 약 10만 건 편중).
- 예상 밖: A안 검증 때 psql 측정(0.69~0.73초)보다 앱에서 S1이 약 0.99초. 기준일 변경(10-29 → 10-30), 프로필 버전 조건을 스칼라 서브쿼리로 바꾼 형태, 바인드 파라미터 처리 차이가 겹친 것으로 보며 개별 기여는 분리하지 않았다.
- 범용(generic) 계획은 2~5배 느리다. 기본 `plan_cache_mode=auto`에서 같은 문장을 7~9번 반복 실행해도 custom 계획을 유지했다(계획에 리터럴 값 표시). 통계 변화로 generic이 선택되면 응답이 크게 느려질 수 있어 운영 관찰 대상이다.
- work_mem 32MB(세션 한정, 2026-10-29 검증)에서도 S1 시간은 줄지 않았다 → 디스크 정렬은 주원인이 아니다.

## 6. 기존 추천 결과와의 동등성

- 무작위 Fixture: `RecommendationSelectionEquivalenceTest`(H2)·`...PostgresTest`(PostgreSQL) 각 240건 불일치 0. 비교 단위는 후보 레코드 전체(신청 상태·마감일·훈련 시작일·상시 모집 여부 포함)·관심 적합도·신청 가능성. 같은 우선순위·날짜의 ID 정렬은 2026-10-29 검증에서 정렬을 뒤집으면 실패함을 확인했다.
- 실제 덤프 9명(S1~S5, 만료 판정, 프로필 버전 변경, 추천 저장, 상위 추천 숨김): 운영 선정과 참조 구현 결과 동일 (`a-plan-after/dump-reference.md`), V0와 After의 HTTP 응답 본문(추천 이유·지역·저장 여부 포함) 바이트 동일 (`a-plan-after/equivalence/`, sha256 a90f67ab…).
- 조건 묶음 변경(버전 변경)은 기존 PostgreSQL 통합 테스트(`staleResultsAndOtherUsersPreferencesNeverLeakIntoRecommendations`)로 확인.

## 7. 회귀 테스트

백엔드 전체 `gradlew test`: 1,093건, 실패 0, 건너뜀 106(환경변수로 켜는 측정·외부 DB 테스트). 추천 관련 PostgreSQL 통합 5건·동등성 H2/PostgreSQL 각 1건(240비교)·단위 테스트 포함.

## 8. Java·SQL 규칙 이중 관리 비용

다음 규칙이 Java(참조·응답 매핑)와 SQL 양쪽에 있다: 최신 판정 기준, 후보 조건(개인화 상태·신청 상태 OPEN/ALWAYS_OPEN·훈련 개강 7일), 가장 이른 열린 마감일, 관심 적합도, 그룹, 우선순위(임박 구간·날짜·신규·ID).
- 한쪽만 바꾸면 결과가 달라진다. SQL 주석에 대응 Java 위치를 적었고, 동등성 테스트(H2·PostgreSQL)가 회귀 테스트로 차이를 잡는다.
- 혼합 규칙(B·C 확보, D 채움)과 최종 순서는 Java 한 곳에만 있다.
- 운영에서 더 이상 쓰지 않는 전체 순회 경로(`findAfter`, `getCandidatesAfter`, `RecommendationEligibilityService`, 숨김 일괄 조회)는 참조 구현·측정용으로 남겼다. 정리 여부는 별도 결정이 필요하다.

## 9. 행동 개인화 적용 시 변경 지점 (기술적 한계)

- 행동 점수가 그룹 안 순서나 그룹 배정을 바꾸면 **SQL의 그룹별 상위 5건 선정 기준도 함께 바꿔야 한다.** Java에서만 점수를 반영하면 SQL이 잘라 낸 후보가 다시 올라올 수 없어 결과가 틀린다.
- 변경 지점: `findTopPerGroup`의 `ranked`(정렬)·`grouped`(그룹) 단계, Java `priority()`·`groupOf()`, 참조 구현과 동등성 테스트.
- 그룹 혼합·설명 생성은 Java에 그대로 둘 수 있다. 점수를 SQL에서 계산하려면 요청 시점에 조인할 수 있는 형태(사용자별 선호 등)가 필요하다. 이번 단계에서는 점수·집계 테이블·캐시를 만들지 않았다.

## 10. 남은 병목

1. 상위 후보 SQL이 추천 가능 후보 수에 비례한다 (관심 미설정·숨김 많은 사용자 약 1초). 데이터가 늘면 같이 늘어난다.
2. 관심 필터가 후보 생성 뒤에 적용되어 드문 관심도 약 0.5초.
3. 플래너 추정 오차와 generic 계획 위험.

현재 수준(8~12초 → 0.02~1.04초)에서 추가 최적화는 필수는 아니라고 본다. 데이터 증가나 응답 목표(예: p95 1초 이하 고정)가 생기면 관심 조건을 앞 단계로 옮기는 방법, 그룹별 상위 N건을 인덱스 순서로 읽는 방법, 계획 고정 등을 측정 후 검토한다.

## 재측정

### 저장소에 포함되는 측정 도구 (`backend/src/test/java/com/team3/backend/recommendation/benchmark/`)

모두 환경변수가 있을 때만 실행되며 일반 `gradlew test`에서는 건너뛴다. DB 주소·비밀번호는 환경변수로만 받는다.

| 파일 | 역할 | 실행 조건 |
|---|---|---|
| `RecoBenchProbe` | JDBC 실행(문장·시간·결과 행·바인드 값), 커넥션 점유, 추천 협력 빈의 메서드별 시간 기록. 빈을 감싸기만 하고 대체하지 않는다 | 아래 테스트가 `@Import`할 때만 |
| `RecoBenchSchemaPlanTest` | 덤프에 없는 사용자 테이블을 만들 Hibernate update 스크립트를 파일로만 생성 (DB에 실행하지 않음) | `YON_RECO_BENCH_PHASE=schema-plan` |
| `RecommendationBenchmarkTest` | `setup`(S1~S4 합성 사용자·실제 판정 엔진), `setup-extra`(S5 숨김 2만 건, 동등성 사용자 E1~E4), `measure`(MockMvc 교차 반복: 응답·SQL·JDBC·커넥션·할당·후보 단계 수), `equivalence`(HTTP 응답 본문 기록) | `YON_RECO_BENCH_DB_URL` + `YON_RECO_BENCH_PHASE` |
| `RecommendationDumpReferenceTest` | 실제 덤프 사용자 9명에서 운영 선정 vs 전체 순회 참조 구현 비교 | `YON_RECO_BENCH_PHASE=dump-reference` |

주요 환경변수: `YON_RECO_BENCH_DB_URL`, `YON_RECO_BENCH_DB_PASSWORD`(application_user), `YON_RECO_BENCH_TODAY`(기준일, 측정 중 실제 날짜와 같아야 함),
`YON_RECO_BENCH_WARMUP`, `YON_RECO_BENCH_ITERATIONS`, `YON_RECO_BENCH_LABEL`, 개선 전 코드 측정 시 `YON_RECO_BENCH_MODE=scan`·`YON_RECO_BENCH_BATCH`·`YON_RECO_BENCH_HIDDEN`
(예상 SQL 수 계산용), After는 `YON_RECO_BENCH_MODE=top-per-group`.

### 로컬에만 있는 환경 (재현 절차)

1. 운영·개발 DB와 분리된 PostgreSQL 16 컨테이너를 만들고 `database/scripts/database-boundaries.sql`로 초기화한다 (작성자 환경: `perf-data/opportunity-recommendation/env/compose.yaml`, 포트 5436).
2. 정규화 덤프(`yon-normalized-20261027.dump`)를 `pg_restore --no-owner --role=application_user`로 복원하고 `ANALYZE`한다.
3. main 배포 상태와 맞추기 위해 `database/scripts/search-title-columns.sql`, `search-indexes.sql`을 application_user로 적용한다.
4. `schema-plan` 단계로 만든 스크립트를 검토한 뒤 사용자 관련 테이블을 만든다 (기존 공고 테이블 변경 없음 확인).
5. `setup` → `setup-extra` → `equivalence`·`measure`·`dump-reference` 순서로 실행한다. 개선 전 코드는 이 커밋 이전 커밋의 worktree에 측정 도구를 복사해 같은 명령으로 실행한다.
