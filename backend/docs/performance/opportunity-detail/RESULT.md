# 공고 상세 조회: 신청 가능성 판정 중복 SQL 제거

대상: `GET /api/opportunities/{opportunityId}` (로그인 사용자의 최신 판정 표시)
측정일: 2026-10-29. 운영 DB·외부 API는 사용하지 않았다.

## 요약

- 최신 판정이 있는 로그인 상세 조회의 SQL이 **정책 10 → 7회, 훈련 11 → 8회**로 줄었다. 판정 Provider 내부 SQL은 4 → 1회다.
- 같은 벤치마크로 개선 전후를 3번씩 번갈아 측정한 결과, 해당 시나리오의 평균 응답 시간 중앙값은 **정책 18.99 → 13.23ms(-30.4%), 훈련 20.26 → 14.04ms(-30.7%)**였다. 작은 고정 픽스처와 MockMvc로 잰 값이며 운영 지연 개선폭이 아니다.
- 같은 실행 안의 "최신 판정(B) − 미판정(C)" 응답 차이는 개선 전 5.2~7.2ms였고 개선 후 -0.1~0.4ms다. 판정이 있을 때만 생기던 추가 비용이 사라졌다.
- 응답 본문(createdAt 제외)은 8개 시나리오 × 6회 모두 같았다. 최신성 판정 SQL은 개선 전후 글자 단위로 같다.

## 문제

`OpportunityDetailService.get()`은 공고·신청기간·조건·지역·유형별 상세를 먼저 읽는다. 그다음 `EligibilityProvider.findFreshEligibility()`를 호출했다.
이 메서드는 최신 판정 결과를 찾으면 `OpportunityInfoAssembler.assemble()`로 공고 요약(`OpportunityInfo`)을 조립하고, 이때 projection SQL 3개를 추가로 실행했다.

1. `opportunities` (`findInfoRows`)
2. `application_windows` (`findRowsByVersionIdIn`)
3. `training_course_details` (`findScheduleRows`, 청년정책 공고에도 실행)

상세 응답의 `EligibilityItem.from()`은 판정의 `status`·`reason`·`conditions`만 쓴다. 조립한 `OpportunityInfo`는 상세 응답에 쓰이지 않고 버려졌다.
Hibernate 1차 캐시는 별도 projection 쿼리를 막지 않으므로, 상세 서비스가 이미 읽은 데이터를 다시 DB에서 읽은 셈이다.
미판정·만료된 판정에서는 조립을 하지 않아 이 비용이 없었다.

## 변경

| 파일 | 변경 |
|---|---|
| `eligibility/api/dto/EligibilityDecision.java` | 새 DTO: `status`, `reason`, `conditions` (공고 요약 없음) |
| `eligibility/api/EligibilityProvider.java` | `findFreshDecision(userId, opportunityId, today)` 추가 |
| `eligibility/api/local/LocalEligibilityProvider.java` | 구현: 기존 `resultRepository.findFresh(...)`를 그대로 쓰고 조립을 생략 |
| `search/service/OpportunityDetailService.java` | `findFreshEligibility` 대신 `findFreshDecision` 호출 |
| `search/dto/OpportunityDetailResponseDto.java` | `EligibilityItem.from(EligibilityDecision)` (응답 필드 동일) |

`findFreshEligibility`와 `getEligibilities`·`findByStatuses` 등 기존 메서드는 바꾸지 않았다. 최신 기준(현재 버전의 조건 묶음, 프로필 버전, 규칙 버전, validUntil)은 같은 `findFresh` 쿼리를 쓰므로 그대로다.

### 기술 선택 이유

- **새 경량 메서드 추가 (채택)**: Provider 경계(호출하는 쪽은 인터페이스와 `api.dto`만 의존)를 지킨다. 같은 `findFresh` 쿼리를 재사용하므로 최신성 로직을 다시 작성하지 않는다. 원격 Provider로 바꿀 때도 계약이 명확하다.
- **`findFreshEligibility`에서 조립 제거 (기각)**: "getEligibilities와 같은 `EligibilitySnapshot` 형식"이라는 기존 계약을 깨고 `opportunity`가 null인 스냅샷을 만든다. 기존 테스트도 공고 요약을 단언한다.
- **상세 서비스가 읽은 공고 데이터를 Provider에 넘기기 (기각)**: 판정 영역이 상세 화면의 엔티티·조회 결과에 의존하게 되어 결합도가 늘어난다.
- **캐시 (기각)**: 판정 최신성(프로필·규칙·조건 묶음 버전, 날짜)을 무효화 조건으로 관리해야 하는데, 이 SQL은 결과를 쓰지도 않으므로 캐시할 이유가 없다.

## 측정 환경과 방법

- Windows 11 Home 10.0.26200, Intel Core Ultra 5 125H(논리 18), RAM 15.5GB, Docker 28.0.4
- 테스트 JVM OpenJDK 25.0.3, Spring Boot 4.1.1, Testcontainers `postgres:16-alpine`(PostgreSQL 16.14)
- 코드: `perf/opportunity-detail` HEAD `a906f61`(origin/main `6ae6745` 포함). Before는 이 커밋의 운영 코드, After는 이 문서의 변경을 적용한 작업 트리다.
- `DB_REPLICA_URL` 미설정(읽기 복제본 라우팅 꺼짐), 수집 스케줄러 꺼짐
- 픽스처: 공고/버전 각 2(청년정책·직업훈련), 신청기간 2, 조건 1, 지역 1, 공고지역 2, 유형별 상세 각 1, 조건 묶음 2, 사용자/프로필 각 3, 판정 결과 4(최신 2·만료 2). 미판정 사용자는 결과가 없다.
- 시나리오: A 비로그인, B 로그인·최신 판정, C 로그인·미판정, D 로그인·만료 판정 × 정책/훈련 = 8개
- 회차마다 시나리오별 워밍업 20회와 측정 100회를 했고, 8개 시나리오를 교차 실행했다. 단일 스레드 MockMvc를 썼다.
- **Before·After를 before1 → after1 → before2 → after2 → before3 → after3 순서로 번갈아 실행했다**(시스템 상태 변화의 영향을 줄이기 위함). Before는 HEAD의 git worktree에 같은 벤치마크 파일을 복사해 실행했다.
- 응답 시간은 `MockMvc.perform` 시작부터 `andReturn` 완료까지다. JDBC 시간은 `PreparedStatement.execute*` 내부의 클라이언트 측 경과 시간 합계이며 PostgreSQL 서버 실행 시간이 아니다.
- Provider 구간은 `EligibilityProvider` 빈을 감싼 프록시가 메서드 이름과 관계없이 표시한다. 그래서 개선 전 `findFreshEligibility`와 개선 후 `findFreshDecision`이 같은 기준으로 계측된다.
- 매 요청마다 단언한 것: HTTP 200, 판정 표시 여부가 시나리오와 일치, SELECT 외 SQL 0, Hibernate prepareStatement 수 = JDBC 실행 수, 회차 내 SQL 수 일정.

## 결과

3회차 각 값의 중앙값이다. 괄호는 3회차의 최소~최대이며 단위는 ms다.

| 시나리오 | SQL 전→후 | Provider SQL 전→후 | 평균 전 | 평균 후 | 변화 | p50 전→후 | p95 전→후 | p99 전→후 | JDBC 합계 전→후 | Provider JDBC 전→후 |
|---|---|---|---:|---:|---:|---|---|---|---|---|
| 정책 A 비로그인 | 5→5 | 0→0 | 9.856 (9.72~12.62) | 9.404 (8.99~9.67) | -4.6% | 9.05→8.88 | 16.20→14.58 | 19.61→17.20 | 5.39→5.05 | 0→0 |
| **정책 B 최신** | **10→7** | **4→1** | 18.994 (17.78~23.50) | 13.225 (12.60~13.27) | **-30.4%** | 18.07→11.86 | 27.14→19.37 | 32.52→26.75 | 11.03→7.18 | 4.56→1.02 |
| 정책 C 미판정 | 7→7 | 1→1 | 13.028 (12.58~16.33) | 13.089 (12.31~13.31) | +0.5% | 11.91→11.56 | 18.87→18.39 | 28.90→26.58 | 7.38→7.22 | 1.08→0.99 |
| 정책 D 만료 | 7→7 | 1→1 | 14.364 (13.40~17.05) | 13.156 (12.88~13.66) | -8.4% | 12.38→11.74 | 24.02→22.27 | 36.52→28.78 | 8.31→7.23 | 1.07→0.99 |
| 훈련 A 비로그인 | 6→6 | 0→0 | 11.146 (10.55~14.10) | 10.521 (10.14~10.59) | -5.6% | 10.16→9.52 | 17.13→16.15 | 25.81→22.14 | 6.55→6.00 | 0→0 |
| **훈련 B 최신** | **11→8** | **4→1** | 20.261 (19.10~24.74) | 14.041 (13.55~14.11) | **-30.7%** | 18.24→13.10 | 29.45→19.63 | 56.16→25.61 | 12.09→7.95 | 4.31→1.02 |
| 훈련 C 미판정 | 8→8 | 1→1 | 14.313 (13.63~17.73) | 14.069 (13.14~14.23) | -1.7% | 13.13→12.45 | 21.52→19.78 | 37.26→26.90 | 8.52→8.25 | 1.05→1.04 |
| 훈련 D 만료 | 8→8 | 1→1 | 15.693 (14.60~18.60) | 14.656 (13.75~14.95) | -6.6% | 13.31→12.49 | 28.15→26.12 | 39.78→44.65 | 9.44→8.11 | 1.13→1.01 |

같은 실행 안의 B − C 평균 응답 차이(ms):

| | 1회차 | 2회차 | 3회차 |
|---|---:|---:|---:|
| 정책 Before | 5.966 | 5.206 | 7.164 |
| 정책 After | 0.185 | -0.089 | 0.284 |
| 훈련 Before | 5.948 | 5.474 | 7.007 |
| 훈련 After | -0.028 | -0.115 | 0.409 |

해석:

- 코드가 바뀐 경로는 B뿐이다. A·C·D의 SQL 수와 SQL 전문은 전후가 같다. A·C·D에서 보이는 -8%~+0.5% 차이는 before3이 전 시나리오에서(비로그인 A 포함) 느렸던 것 같은 실행 간 편차로 보이며, 개선 효과로 주장하지 않는다.
- B의 개선은 회차 간 범위가 겹치지 않는다(Before 최소 17.78ms > After 최대 13.27ms, 훈련도 같음). 개선 후 B는 C와 같은 수준이 되었다.
- p99는 표본 100개 중 상위 1~2개 값이라 변동이 크다(예: 훈련 D 39.78→44.65). 근거로 쓰지 않는다.

## 제거된 SQL (B 정책, 첫 측정 요청)

Before:

```text
1-5. [Detail]   opportunities / application_windows / opportunity_conditions / opportunity_regions / youth_policy_details
6.   [Provider] opportunities ⋈ opportunity_versions, eligibility_set_results (최신 판정 조회 findFresh)
7.   [Provider] opportunities ... where id in (?)            ← 제거
8.   [Provider] application_windows ... where version_id in (?)  ← 제거
9.   [Provider] training_course_details ... where version_id in (?) ← 제거
10.  [Detail]   user_opportunity_preferences (저장 여부)
```

After: 위의 1~6과 10만 남는다. 훈련 공고는 끝에 `opportunity_versions` 단건 조회(지역 검증용)가 1회 더 있으며 전후가 같다. SQL 전문은 `perf-data/opportunity-detail/comparison/*-raw.md`에 있다.

## 정합성 검증

- **응답 계약**: 벤치마크가 회차마다 첫 측정 요청의 응답 본문을 기록했다. createdAt(픽스처 생성 시각)을 제외하면 8개 시나리오 × Before 3회·After 3회가 모두 같다.
- **최신성 로직**: 개선 전후 Provider의 판정 조회 SQL 문자열이 같다(같은 `findFresh` 재사용).
- **회귀 테스트**
  - 백엔드 전체 `gradlew test`: 1,085건 중 실패 0, 건너뜀 102(환경변수로 켜는 테스트)
  - `OpportunityDetailControllerTest`·`OpportunityDetailPostgresTest`: 각 10건 통과. 추가한 테스트 `eligibilityForOldProfileOrOtherConditionSetIsHidden`은 이전 프로필 버전의 결과와, 현재 버전과 다른 조건 묶음의 결과를 상세 응답에서 숨기는지 확인한다. 기존 테스트는 최신·만료·미판정·이전 규칙·다른 사용자·프로필 없음과 status/reason/conditions 매핑을 검증한다.
  - `LocalEligibilityProviderTest`: 12건 통과. 추가한 테스트는 `findFreshDecision`이 `findFreshEligibility`와 같은 status/reason/conditions를 돌려주는지, 프로필 버전이 바뀌면 비고 프로필이 없거나 공고가 없으면 비는지 확인한다. 이 테스트는 `YON_TEST_DB_URL`이 필요하다. 기존 DB를 초기화하지 않도록 `--rm` 일회용 PostgreSQL 컨테이너에서 실행한 뒤 삭제했다.

## 한계

- 픽스처가 작다(공고 2건). 실제 데이터량에서 projection 3회의 비용, 인덱스 효율, 네트워크 왕복(앱–DB가 분리된 운영 환경)은 반영하지 못했다. 운영에서는 왕복 3회가 줄어든 만큼 절감폭이 더 클 수도, 작을 수도 있다.
- MockMvc 인프로세스 측정이라 HTTP·TLS·직렬화 전송 비용은 빠져 있다. 테스트용 JDBC 프록시·Provider 프록시 오버헤드는 들어 있다.
- 같은 날 다른 시각의 측정(`perf-data/opportunity-detail/before/`)은 같은 Before 코드인데도 평균이 약 2배였다. 절대값은 시스템 상태에 크게 좌우되므로 같은 시점의 교차 측정 결과만 비교한다.
- GC/JIT 통계와 서버 측 `EXPLAIN ANALYZE`는 측정하지 않았다.
- 과거 기준선(2026-10-21, `perf-data/opportunity-detail/baseline/BEFORE.md`)은 판정 결과 모델(사용자 × 공고)이 달랐던 시점의 값이라 개선폭 계산에 쓰지 않았다.
- `findFreshEligibility`는 운영 코드에서 더 이상 호출되지 않는다. Provider 계약의 일부라 이번 범위에서는 남겨 두었다.

## 재측정

벤치마크는 시간이 걸리고 Docker가 필요하므로 기본 `gradlew test`에서는 건너뛴다(`@EnabledIfEnvironmentVariable`). Docker Desktop을 실행한 뒤 PowerShell에서:

```powershell
cd backend
$env:YON_DETAIL_BENCH='true'
$env:YON_DETAIL_BENCH_LABEL='after'   # 선택: 원자료 머리글에 기록
.\gradlew.bat test --tests '*OpportunityDetailBenchmarkTest' --console=plain --rerun
```

선택적으로 `YON_DETAIL_BENCH_WARMUP`(기본 20)과 `YON_DETAIL_BENCH_ITERATIONS`(기본 100)를 양의 정수로 지정할 수 있다.
원자료(통계, 첫 요청 SQL 전문, 응답 본문)는 `backend/build/reports/opportunity-detail-benchmark/raw.md`에 생성된다.
Before를 다시 재려면 이 변경 이전 커밋을 worktree로 꺼내 벤치마크 두 파일(`DetailSqlProbe.java`, `OpportunityDetailBenchmarkTest.java`)을 복사해 같은 명령으로 실행한다.
교차 실행·집계 스크립트는 `perf-data/opportunity-detail/comparison/run-interleaved.sh`, `aggregate.py`에 있다.

## 원자료 위치 (`perf-data/`, Git 추적 제외)

| 경로 | 내용 |
|---|---|
| `perf-data/opportunity-detail/baseline/` | 과거 기준선 `BEFORE.md`(2026-10-21) 원본과 설명 |
| `perf-data/opportunity-detail/before/` | 최신 main 기준 Before 단독 측정(2026-10-29, 교차 측정 전) |
| `perf-data/opportunity-detail/after/` | After 3회차 원자료 |
| `perf-data/opportunity-detail/comparison/` | 교차 측정 6회차 원자료·로그, `summary.md` 집계 |
