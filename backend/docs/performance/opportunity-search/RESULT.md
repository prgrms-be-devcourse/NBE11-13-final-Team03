# 통합 검색: 복합 필터 SQL 재구성, 인덱스, 검색 트랜잭션 JIT OFF

대상: `GET /api/opportunities` (청년정책·직업훈련 통합 검색 목록)
측정일: 2026-10-27~28. 모든 수치는 독립 실험 DB(`localhost:5433/yon`)에서 쟀다. 개발 DB(5432)·운영 DB·외부 API는 사용하지 않았다.
관련 PR: [#102](https://github.com/prgrms-be-devcourse/NBE11-13-final-Team03/pull/102)

## 요약

- 출발점(2026-10-27, 최적화 이전 SQL·인덱스 없음·JIT ON)에서 청년정책+서울+마감일 우선(S11) API 중앙값은 **17,511.6ms**, 전체 최신순(S1)은 **104.5ms**였다.
- 같은 SQL의 `EXPLAIN ANALYZE`에서 실행 14,191.8ms 중 JIT가 13,510.7ms(함수 2,490개)였다(S3 목록 SQL, 인덱스 없음·JIT ON). 같은 SQL을 별도 실행으로 JIT ON/OFF 비교했을 때 실행 시간은 15,891 → 429ms였다. 병목은 데이터 양이 아니라 PostgreSQL JIT 컴파일이었다.
- 변경은 세 가지다. ① 마감일 정렬·지역 필터 Criteria 재구성(S3 목록 SQL 서브쿼리 51 → 21개, 바인딩 자리표시자 188 → 80개) ② 부분·복합 인덱스 3개 스크립트 ③ 검색 트랜잭션 한정 `SET LOCAL jit = off`.
- 각 효과는 조건을 고정해 따로 쟀다. 서로 다른 회차의 수치는 합산하지 않는다.
  - **JIT OFF만의 효과** (2026-10-28, 개선 SQL·인덱스 없음): S11 중앙값 **5,647 → 406ms**.
  - **SQL 재구성 + 인덱스의 효과** (2026-10-28, 양쪽 JIT OFF): S11 중앙값 **658 → 138ms**, S3 **813 → 174ms**.
  - **최종 상태 비교** (2026-10-28 같은 세션, Before = 이전 SQL·인덱스 없음·JIT ON, After = 현재 SQL·인덱스·JIT OFF): S11 중앙값 **15,141 → 138ms**, S3 **10,620 → 174ms**. 같은 세션의 Before 2회차는 S11 18,334ms였다(세션 내 변동 +21%).
- 대조군 S1(전체 최신순)은 개선되지 않았다. JIT ON/OFF 비교에서 92 → 101ms(0.91x), 최종 비교에서 77 → 90ms(0.85x)다.
- 응답 결과(totalElements·첫 페이지 공고 ID 순서)는 모든 회차에서 개선 전후가 같았다.

## 문제

검색 서비스(`OpportunitySearchService`)는 필터(유형·지역·모집 상태)와 마감일 우선 정렬을 Criteria API의 `EXISTS` 서브쿼리로 펼친다. 실제로 걸러지는 행은 적지만 플래너 비용 추정치가 수백만까지 올라갔다. 출발점 S3 목록 SQL cost는 1,638,359, S11은 4,407,907이다(`sql-restructure/sql/explain-summary.txt`).

실험 DB 설정은 `jit = on`, `jit_above_cost = 100000`, `jit_inline_above_cost = jit_optimize_above_cost = 500000`이었다(`baseline/sql/db-settings.txt`). 비용이 임계값을 넘으면 PostgreSQL이 LLVM 인라인·최적화 컴파일을 켜고, 컴파일 시간이 실제 실행 시간을 크게 넘었다.

출발점 S3 목록 SQL 계획(`baseline/sql/S3-list.plan.txt`)의 JIT 항목:

```text
JIT:
  Functions: 2490
  Timing: Generation 215.910 ms, Inlining 342.110 ms, Optimization 7287.947 ms, Emission 5664.731 ms, Total 13510.698 ms
Execution Time: 14191.800 ms
```

마감일 우선 정렬은 상태(OPEN·UPCOMING·ALWAYS_OPEN·CLOSED) 판정과 상태별 마감일을 같은 Criteria 식으로 여러 정렬 항목에 재사용했다. Criteria는 재사용 횟수만큼 서브쿼리를 다시 펼쳐 S3 목록 SQL 한 문장에 서브쿼리가 51개 생겼다.
지역 필터는 유형을 지정한 검색에서도 반대 유형(정책 거주지 / 훈련 시행 지역)의 판정 가지를 SQL에 남겼고, 바깥 술어가 OR이라 `EXISTS`가 세미조인으로 승격되지 못했다.

## 변경

| 파일 / 커밋 | 변경 |
|---|---|
| `search/service/OpportunitySearchService.java` (`4673a55`) | 마감일 우선 정렬: 공유하던 대표 날짜 식을 오름차순 묶음·내림차순 묶음 날짜 식으로 나눠 각 한 번씩만 만들고 `Nulls.LAST`로 NULL 순서를 명시. 정렬 규칙은 같다 |
| 같은 파일 (`4673a55`) | 지역 필터: 유형을 지정한 검색에서는 해당 유형의 판정만 만든다(유형 미지정만 두 판정 유지). 청년정책 지역 판정은 OR 위치를 버전 서브쿼리 안으로 옮긴 `policyVersionInRegion`으로 바꿨다(거주 지역 포함 또는 전국 대상, 규칙 동일) |
| `database/scripts/search-indexes.sql` 외 2개 (`1fc4b64`) | 인덱스 3개 생성·점검·롤백 스크립트. `CREATE INDEX CONCURRENTLY IF NOT EXISTS`, 정의 불일치·INVALID 인덱스 사전 차단 |
| `OpportunitySearchService.java`, `OpportunitySearchPostgresTest.java` (`29f3ce2`) | 검색 트랜잭션에서만 `SET LOCAL jit = off`. PostgreSQL이 아니면 실행하지 않는다. 설정이 트랜잭션 밖으로 새지 않는지 테스트 |

인덱스(실험 DB 기준 크기, `index-exp/sql/index-meta.tsv`):

| 이름 (실험 시 라벨) | 정의 | 크기 |
|---|---|---|
| `idx_opportunity_conditions_nationwide` (A1) | `opportunity_conditions(version_id)` WHERE REGION·residenceRegion·status=NATIONAL (183,598행 중 911행) | 40 kB |
| `idx_application_windows_active` (B1) | `application_windows(version_id, start_date, end_date)` WHERE VALID·FIXED (142,652행 중 2,555행) | 96 kB |
| `idx_opportunities_type_latest` (C1) | `opportunities(opportunity_type, created_at DESC, id DESC)` | 8,088 kB |

### 기술 선택 이유

- **검색 트랜잭션 한정 `SET LOCAL jit = off` (채택)**: 서버 전역 설정을 바꾸지 않고, 트랜잭션이 끝나면 커넥션 설정이 원래대로 돌아간다. 다른 DB 작업(수집·추천 등)의 JIT 판단에 영향을 주지 않는다.
- **전역 `jit = off` 또는 임계값 상향 (기각)**: 운영 DB 설정 변경이 필요하고, 검색 외 질의까지 영향을 받는다.
- **후보 공고를 먼저 좁히는 서브쿼리(prefilter) (기각)**: 플래너 cost가 크게 내려가 JIT는 발동하지 않았지만, JIT OFF 실행 시간이 기준보다 3.2~5.9배 나빠졌다(아래 "SQL 대안 후보"). cost만 보고 고르면 안 되는 근거다.
- **인덱스 후보 A2·D1 (미채택)**: A2(`condition_type, condition_field, version_id`, 10,184 kB)와 D1(`region_role, region_code, version_id`, 9,232 kB)도 단독으로 실험했다. 2차 조합 실험(`round2-summary.txt`)부터는 A1·B1·C1만 남겼다. 시나리오별 단독 결과는 `index-exp/sql/single-candidate-summary.txt`에 있으며, 미채택 사유를 따로 적은 기록은 없다.

## 측정 환경과 방법

- DB: 독립 실험 컨테이너 `yon-search-perf-postgres-db-1`, PostgreSQL 16.14, `localhost:5433/yon`. `shared_buffers` 16384×8kB, `work_mem` 4096kB, `max_parallel_workers_per_gather` 2.
- backend: `localhost:8081`, `ddl-auto=none`, 스케줄러 비활성, 실험 DB로만 접속.
- 데이터: 공고 140,657건.
- API 측정: Python 하네스(`baseline/measure.py`, 이후 회차가 재사용)가 시나리오마다 keep-alive 커넥션 1개로 **단일 클라이언트 순차 요청**을 보냈다. 동시 요청 부하 테스트가 아니다. 시나리오마다 warmup 후 측정했다(대표값: S1 warmup 10/측정 30, S3·S11 2/10, S5·S6·S12·S13 5/20, S9 3/20. 8차 S5만 5/40). 응답 시간은 클라이언트 측 경과 시간이다.
- SQL 측정: 실제 요청에서 캡처한 SQL의 바인딩을 리터럴로 바꿔 `EXPLAIN (ANALYZE ...)`를 실행했다. `sql-restructure`·`index-exp`·`remaining`은 3회 반복의 중앙값이다. JIT OFF는 세션 `SET jit = off`로 바꿨다.
- 시나리오(서울 = `1100000000`, 모두 page=0, size=20. S8만 page=500):

| ID | 조건 |
|---|---|
| S1 | 전체 최신순 (대조군) |
| S3 | 청년정책 + 마감일 우선 정렬 |
| S5 | 지역 서울 (유형 미지정) |
| S6 | 모집 상태 OPEN 단독 |
| S9 | 서울 + OPEN |
| S11 | 청년정책 + 서울 + 마감일 우선 정렬 |
| S12 | 직업훈련 + 서울 (5차부터) |
| S13 | 청년정책 + 서울 최신순 (5차부터) |

- **회차마다 측정 조건이 다르다.** 같은 BASE 조건을 같은 날 3회 쟀을 때 API 중앙값이 JIT ON 15~67%, JIT OFF 12~121% 움직였다(`index-verify/analysis-A.txt`). 같은 세션 안에서도 최종 Before 두 회차가 -9%~+30% 차이 났다. 그래서 같은 회차 안의 전후 비교만 쓰고, 1.0~1.4배 차이는 개선으로 주장하지 않는다.

## 결과

단위는 ms. 별도 표시가 없으면 API 중앙값이다.

### 1. 출발점 (2026-10-27, 이전 SQL·인덱스 없음·JIT ON, `baseline/`)

| 시나리오 | n / warmup | 중앙값 | p95 | 목록 SQL exec (JIT ON) | 목록 SQL exec (JIT OFF) | 목록 SQL 서브쿼리 |
|---|---|---:|---:|---:|---:|---:|
| S1 전체 최신순 | 30 / 10 | 104.5 | 118.5 | 43 | 26 | 0 |
| S3 청년정책+마감일 | 10 / 2 | 12,701.4 | 15,052.5 | 15,891 | 429 | 51 |
| S5 서울 | 30 / 5 | 2,894.7 | 3,789.1 | 899 | 143 | 6 |
| S9 서울+OPEN | 20 / 3 | 4,372.2 | 5,395.6 | 1,736 | 377 | 10 |
| S11 청년정책+서울+마감일 | 10 / 2 | 17,511.6 | 18,904.0 | 12,819 | 420 | 57 |

SQL exec는 `baseline/sql/exec-times-jit-{on,off}.tsv`의 `EXPLAIN ANALYZE` Execution Time이다. S3 목록 SQL의 JIT ON 기록은 JIT Total 15,155.6ms·함수 2,490개, S11은 13,132.7ms·2,879개다. 계획 본문(`S3-list.plan.txt`, 실행 14,191.8ms·JIT 13,510.7ms)은 별도 실행이라 값이 다르다.

### 2. JIT 가설 검증 (2026-10-27, 이전 SQL·인덱스 없음, JIT ON → OFF → ON, `jit-check/`)

| 시나리오 | ON-1 중앙 | OFF 중앙 | ON-2 중앙 | 배수 (ON 평균 / OFF 평균) |
|---|---:|---:|---:|---:|
| S1 | 126.4 | 94.3 | 91.0 | 1.2x |
| S3 | 13,767.9 | 672.9 | 11,721.4 | 18.9x |
| S5 | 2,532.1 | 427.2 | 2,582.4 | 6.0x |
| S9 | 4,037.7 | 840.8 | 4,179.4 | 4.9x |
| S11 | 14,581.3 | 808.4 | 15,962.9 | 18.9x |

ON 두 회차 사이 차이는 2.0~28.0%다. JIT를 끄면 API 수준에서도 같은 방향으로 재현됐다.

### 3. 마감일 정렬 Criteria 재구성 (2026-10-27, 인덱스 없음, `sql-restructure/`)

S3 목록 SQL(캡처된 실제 문장 기준) 서브쿼리 51 → 21개, 바인딩 자리표시자 188 → 80개.

`EXPLAIN ANALYZE` (3회 중앙값):

| SQL | JIT | cost 전→후 | exec 전→후 | JIT Total 전→후 | JIT 함수 전→후 |
|---|---|---|---|---|---|
| S3 목록 | ON | 1,638,359 → 679,247 | 11,683 → 4,994 | 10,996 → 4,712 | 2,490 → 630 |
| S3 목록 | OFF | 1,638,359 → 679,247 | 628 → 352 | - | - |
| S11 목록 | ON | 4,407,907 → 3,931,393 | 16,438 → 8,426 | 17,504 → 8,877 | 2,879 → 883 |
| S11 목록 | OFF | 4,407,907 → 3,931,393 | 711 → 379 | - | - |

API 중앙값 (`final-comparison.txt`. 개선 전 JIT ON은 `jit-check` ON 두 회차의 대표값):

| 시나리오 | 전 (JIT ON) | 후 (JIT ON) | 전 (JIT OFF) | 후 (JIT OFF) |
|---|---:|---:|---:|---:|
| S1 | 109 | 100 | 94 | 89 |
| S3 | 12,745 | 5,494 | 673 | 301 |
| S5 | 2,557 | 2,501 | 427 | 330 |
| S9 | 4,109 | 4,118 | 841 | 724 |
| S11 | 15,272 | 7,899 | 808 | 375 |

JIT ON에서 S3·S11은 절반 수준으로 줄었지만 여전히 5~8초였다. cost가 임계값 500,000을 계속 넘어 JIT가 발동했기 때문이다.

### 4. 지역 필터 유형 분리 (2026-10-27, 인덱스 없음, `region-filter/`)

| 시나리오 | 전 중앙 (ON) | 후 중앙 (ON) | 전 중앙 (OFF) | 후 중앙 (OFF) |
|---|---:|---:|---:|---:|
| S11 청년정책+서울+마감일 | 8,605 | 7,671 | 501 | 376 |
| S12 직업훈련+서울 | 1,953 | 195 | 279 | 145 |
| S13 청년정책+서울 | 2,972 | 1,795 | 139 | 160 |

S12는 직업훈련 지역 판정만 남으면서 목록 SQL cost가 3,599,631 → 13,254로 내려가 JIT가 발동하지 않게 됐다(`sql/type-compare.tsv`). S13은 JIT OFF에서 0.87x로 나빠졌고, 다음 회차의 `policyVersionInRegion`으로 이어졌다. SQL을 바꾸지 않은 S1·S5·S6·S9의 변화(1.05~1.40x)는 회차 간 변동으로 보고 개선으로 주장하지 않는다.

### 5. 청년정책 지역 판정과 SQL 대안 후보 (2026-10-27, 인덱스 없음, `remaining/`)

채택한 `policyVersionInRegion` 적용 전후 API 중앙값:

| 시나리오 | 전 (ON) | 후 (ON) | 전 (OFF) | 후 (OFF) |
|---|---:|---:|---:|---:|
| S11 | 7,019 | 6,260 | 400 | 356 |
| S13 | 1,728 | 138 | 163 | 112 |

검토한 SQL 대안 후보 (`sql/candidate-summary.txt`, `EXPLAIN ANALYZE` 3회 중앙값, 모든 후보 결과 건수 동일):

| 경로 | 후보 | cost | JIT 발동 | exec (ON) | exec (OFF) | OFF 배수 | 판정 |
|---|---|---:|---|---:|---:|---:|---|
| S5 | A0 기준 | 3,599,330 | 예 | 1,543 | 226 | 1.00x | 기준 |
| S5 | A1 prefilter | 26,529 | 아니오 | 949 | 902 | 0.25x | 기각 |
| S5 | A2 prefilter-any-role | 23,003 | 아니오 | 961 | 1,026 | 0.22x | 기각 |
| S6 | B0 기준 | 2,385,390 | 예 | 1,501 | 329 | 1.00x | 기준 |
| S6 | B1 prefilter | 16,288 | 아니오 | 1,829 | 1,927 | 0.17x | 기각 |
| S13 | C0 기준 | 2,398,796 | 예 | 1,206 | 76 | 1.00x | 기준 |
| S13 | C2 in-versions | 8,384 | 아니오 | 27 | 25 | 3.00x | 채택 (`policyVersionInRegion`) |
| S9 | D0 기준 | 5,974,436 | 예 | 2,470 | 460 | 1.00x | 기준 |
| S9 | D1 prefilter-both | 40,045 | 아니오 | 1,564 | 1,487 | 0.31x | 기각 |
| S9 | D2 prefilter-region-only | 29,652 | 아니오 | 1,479 | 1,503 | 0.31x | 기각 |

판정 기준은 "JIT ON 1.2배 이상 개선 AND JIT OFF 0.9배 이상"이다. 후보 선별 조건을 추가한 prefilter 계열은 cost가 낮아져 JIT가 꺼졌지만, JIT OFF 실행은 기준보다 3.2~5.9배 느려졌다. JIT만 피하는 SQL 변경은 JIT OFF 적용 뒤에는 손해다. 표에 없는 A3·C1·D3도 같은 파일에 있다.

### 6. 인덱스 (2026-10-27~28, 개선 SQL, `index-exp/`·`index-verify/`)

8차 검증 (`index-verify/analysis-A.txt`, API 중앙값. 기준 = 같은 날 BASE 3회의 중앙값):

| 시나리오 | JIT | 기준 (인덱스 없음) | A1+C1 | A1+B1+C1 |
|---|---|---:|---:|---:|
| S11 청년정책+서울+마감일 | OFF | 228 | 157 | 104 |
| S11 청년정책+서울+마감일 | ON | 3,472 | 3,088 | 2,508 |
| S13 청년정책+서울 | OFF | 60 | 20 | 27 |
| S13 청년정책+서울 | ON | 72 | 22 | 25 |
| S12 직업훈련+서울 | OFF | 65 | 35 | 45 |
| S5 서울 | OFF | 205 | 157 | 162 |
| S9 서울+OPEN | OFF | 427 | 334 | 297 |
| S6 OPEN 단독 | OFF | 254 | 239 | 240 |
| S1 전체 최신순 | OFF | 52 | 50 | 52 |

- S11 JIT OFF 기준값은 BASE 3회가 206~394ms로 91% 움직였다. 228 → 104ms는 이 변동폭과 같이 읽어야 한다.
- S13·S12는 A1+B1+C1이 A1+C1보다 약간 느렸다(B1 증분 0.73~0.92x). B1은 S11 JIT OFF에서만 1.51x 추가 효과가 있었다. 마감일 정렬 서브쿼리가 `application_windows`를 보기 때문이다.
- JIT ON 상태에서는 인덱스를 넣어도 S11이 2.5초 이상이었다. 인덱스만으로는 JIT 발동을 막지 못한다(S5·S6·S9·S11 목록 SQL cost가 계속 50만 이상, `index-exp/sql/round2-summary.txt`).
- 최종 상태 계획(`final-after/index-usage.txt`, JIT OFF): S11 목록 SQL은 A1·B1·C1을 모두 쓰고 168.1ms, S12 목록은 C1으로 0.5ms, S13 목록은 A1·C1으로 1.3ms. 16개 계획 모두 JIT가 발동하지 않았다.

쓰기 비용 (`index-verify/logs/write-cost-v2.log`, 20,000행 복제 테이블에서 C1 유무, 5라운드 순서 교차 중앙값):

| 작업 | 인덱스 없음 | C1 있음 | 증가 |
|---|---:|---:|---:|
| INSERT 20,000행 | 61.7 | 93.4 | +51% |
| 인덱스 컬럼 UPDATE | 191.6 | 258.4 | +35% |
| 비인덱스 컬럼 UPDATE | 84.1 | 122.9 | +46% |

A1·B1은 부분 인덱스라 대상 행이 적다. 7차 1차 측정(`index-exp/logs/write-cost.log`)에서 C1만 INSERT 25.4 → 50.5ms로 늘었고 A1·B1은 늘지 않았다(1회 측정이라 참고치).

### 7. JIT OFF 적용 (2026-10-28, 개선 SQL·인덱스 없음, JIT ON ↔ OFF, `jit-apply/`)

PR #102 본문의 JIT ON/OFF 표의 원자료다.

| 시나리오 | n | ON 중앙 | ON p95 | OFF 중앙 | OFF p95 | 중앙 배수 |
|---|---:|---:|---:|---:|---:|---:|
| S11 청년정책+서울+마감일 | 10 | 5,647 | 6,762 | 406 | 490 | 13.92x |
| S5 서울 | 20 | 2,370 | 2,823 | 341 | 421 | 6.96x |
| S9 서울+OPEN | 20 | 4,716 | 5,430 | 739 | 914 | 6.38x |
| S6 OPEN 단독 | 20 | 1,914 | 3,150 | 444 | 502 | 4.32x |
| S12 직업훈련+서울 | 20 | 179 | 408 | 137 | 311 | 1.30x |
| S13 청년정책+서울 | 20 | 121 | 159 | 107 | 136 | 1.13x |
| S1 전체 최신순 (대조) | 30 | 92 | 115 | 101 | 120 | 0.91x |

S12·S13·S1은 이미 JIT가 발동하지 않는 SQL이라 차이가 변동폭 안이다. S1은 `SET LOCAL` 1문장이 추가되는 만큼 오히려 느려졌을 수 있다(아래 "한계").

### 8. SQL 재구성 + 인덱스만의 효과 (2026-10-28 16:16~16:24, 양쪽 JIT OFF, `before-jitoff/`)

Before = 최적화 이전 SQL(`40c6618`) + 인덱스 없음 + JIT OFF(JDBC URL 옵션), After = 현재 SQL(`ce5d03a`) + 인덱스 A1·B1·C1 + JIT OFF(`SET LOCAL`). 두 상태의 차이는 SQL 구조와 인덱스뿐이다.

| 시나리오 | Before 중앙 | Before p95 | After 중앙 | After p95 | 중앙 배수 | Before 2회차 중앙 |
|---|---:|---:|---:|---:|---:|---:|
| S11 청년정책+서울+마감일 | 658 | 713 | 138 | 167 | 4.76x | 751 |
| S3 청년정책+마감일 | 813 | 1,013 | 174 | 239 | 4.68x | 842 |
| S13 청년정책+서울 | 180 | 268 | 40 | 45 | 4.51x | 173 |
| S12 직업훈련+서울 | 286 | 347 | 98 | 141 | 2.91x | 318 |
| S5 서울 | 384 | 495 | 272 | 345 | 1.41x | 342 |
| S1 전체 최신순 (대조) | 116 | 164 | 90 | 118 | 1.28x | 106 |
| S9 서울+OPEN | 696 | 833 | 632 | 807 | 1.10x | 746 |
| S6 OPEN 단독 | 431 | 605 | 427 | 559 | 1.01x | 460 |

S5·S1·S9·S6(1.01~1.41x)은 변동폭 안이라 개선으로 주장하지 않는다.

참고로 SQL 재구성만의 계획 차이(둘 다 인덱스 없음·JIT OFF, 서로 다른 날의 단일 `EXPLAIN ANALYZE`): S11 목록 SQL의 shared hit는 63,310 → 62,772(-1%)인데 실행은 817.8 → 158.6ms였다(`before-jitoff/sql/plan-before-S11-jit-off.txt`, `index-exp/sql/plan-BASE-S11-list.txt`). 읽은 버퍼 양보다 계획 구조가 차이를 만들었다.

### 9. 최종 상태 비교 (2026-10-28 15:07~15:40, 같은 세션, `final-after/`)

Before = 최적화 이전 SQL(`40c6618`) + 인덱스 없음 + JIT ON, After = 현재 SQL + 인덱스 A1·B1·C1 + JIT OFF. 순서는 Before 1회차 → 인덱스 생성(`search-indexes.sql`) → After → 인덱스 제거(`rollback-search-indexes.sql`) → Before 2회차다.

| 시나리오 | Before 1 중앙 | Before 1 p95 | After 중앙 | After p95 | Before 2 중앙 | 중앙 배수 (B1 기준) |
|---|---:|---:|---:|---:|---:|---:|
| S11 청년정책+서울+마감일 | 15,141 | 17,551 | 138 | 167 | 18,334 | 109.49x |
| S3 청년정책+마감일 | 10,620 | 11,257 | 174 | 239 | 12,346 | 61.05x |
| S13 청년정책+서울 | 2,321 | 2,896 | 40 | 45 | 3,017 | 58.24x |
| S12 직업훈련+서울 | 1,822 | 2,238 | 98 | 141 | 2,225 | 18.56x |
| S5 서울 | 2,299 | 2,603 | 272 | 345 | 2,379 | 8.44x |
| S9 서울+OPEN | 4,174 | 4,882 | 632 | 807 | 5,355 | 6.60x |
| S6 OPEN 단독 | 1,813 | 2,055 | 427 | 559 | 1,656 | 4.25x |
| S1 전체 최신순 (대조) | 77 | 108 | 90 | 118 | 100 | 0.85x |

이 표는 세 변경을 모두 적용한 상태와 적용 전 상태를 한 세션에서 직접 잰 값이다. 개별 변경의 기여도는 7·8번 표로 따로 본다. 2026-10-27 출발점(S11 17,512ms)과는 측정 날짜가 달라 직접 비교하지 않는다(같은 Before 조건인데 S11 -14%, S1 -26% 차이).

## 정합성 검증

- **응답 결과**: 회차마다 전후 상태 × 측정 지점에서 totalElements와 첫 페이지 공고 ID 20개 순서를 지문으로 기록해 비교했다. 모든 회차에서 일치했다. 가장 넓은 비교는 8차(`index-verify/analysis-C.txt`)로 6개 조건 × JIT ON/OFF × 2지점 = 시나리오당 지문 24개, HTTP 비200 응답 0건이다.
- S6·S9의 totalElements는 2026-10-27 회차(133,335·59,931)와 2026-10-28 회차(129,495·58,614)가 다르다. 모집 상태가 기준일(오늘)로 계산되기 때문이며, 같은 회차 안의 전후는 같다.
- **SQL 대안 후보**: 모든 후보의 결과 건수가 기준과 같은지 확인한 뒤 실행 시간을 비교했다(`remaining/sql/candidate-summary.txt`).
- **인덱스 스크립트**: 생성·점검·롤백 3종을 실험 DB에서 실행해 이름 충돌 차단, INVALID 인덱스 가드, 롤백 동작을 확인했다(`index-verify/logs/verify-*.log`, `collision-*.log`, `invalid-*.log`). 최종 측정의 인덱스 생성·제거도 저장소 스크립트를 그대로 썼다.
- **JIT 설정 범위**: `OpportunitySearchPostgresTest`의 `searchTurnsOffJitInsideItsOwnTransaction`·`jitSettingDoesNotLeakOutOfTheSearchTransaction`이 검색 트랜잭션 안에서만 `jit = off`이고 끝나면 `on`으로 돌아오는지 확인한다.

## 한계

- **PR #102 본문에는 JIT ON/OFF 비교 표(7번)만 있다.** 출발점, SQL 재구성, 지역 필터, 인덱스, SQL 대안 후보, 최종 비교 수치는 이 문서가 유일한 기록이다. 원자료(`perf-data/`)는 Git 추적 대상이 아니라 작성자 로컬에만 있다.
- **인덱스는 운영 DB에 적용되지 않았다.** 모든 인덱스 수치는 실험 DB 기준이다. 운영 적용은 테이블 소유 담당자와 협의가 남아 있다. 코드로 반영된 것은 SQL 재구성과 `SET LOCAL jit = off`이고, 인덱스는 스크립트로만 저장소에 있다. 인덱스 없이 JIT OFF만 적용된 상태의 수치는 7번 표다.
- **인덱스 쓰기 비용은 20,000행 복제 테이블의 단일 세션 INSERT/UPDATE로만 쟀다.** 실제 수집·정규화 파이프라인(`data-service`)의 배치 크기, 동시 쓰기, 재실행 시 갱신 패턴에서의 영향은 측정하지 않았다. C1은 INSERT +51%였고 셋 중 가장 크다(8,088 kB).
- **가벼운 검색은 `SET LOCAL jit = off` 1문장이 매 요청 추가된다.** JIT가 원래 발동하지 않던 대조군 S1은 JIT ON/OFF 비교에서 92 → 101ms(0.91x), 최종 비교에서 77 → 90ms(0.85x)였다. 변동폭 안의 차이라 오버헤드 크기를 분리해 재지는 않았지만, 이득이 없는 요청에도 왕복 1회가 붙는다.
- 측정은 단일 클라이언트 순차 요청이다. 동시 요청에서의 처리량·커넥션 점유·DB CPU는 측정하지 않았다. 측정 PC 사양은 원자료에 기록하지 않았다.
- 회차 간·회차 내 변동이 크다(같은 조건 3회 JIT ON 15~67%, JIT OFF 12~121%). 1.0~1.4배 차이는 개선으로 해석하지 않는다.
- `shared read`는 OS 페이지 캐시에서 올 수 있어 물리 디스크 I/O와 같지 않다.
- `sql-restructure` 회차는 계획 본문을 저장하지 않아 이 단계만의 버퍼·정렬 정보는 없다.
- 읽기 복제본 라우팅은 이 측정에 포함되지 않았다. 운영 검색은 복제본에서 실행되므로 인덱스 사용 여부는 복제본에서 다시 확인해야 한다(`search-indexes-check.sql` 4·6번).

## 재측정

### 저장소에 포함된 도구

| 경로 | 역할 |
|---|---|
| `database/scripts/search-indexes.sql` | 인덱스 3개 생성. `application_user`로, 트랜잭션 없이(자동 커밋) 실행. 정의 불일치·INVALID 인덱스가 있으면 멈춘다 |
| `database/scripts/search-indexes-check.sql` | 읽기 전용 점검. 1~3·5번은 primary에서(존재·valid·정의·크기), 4·6번은 검색이 도는 서버에서(사용 통계, S11 등 대표 질의 `EXPLAIN (ANALYZE, BUFFERS)`) |
| `database/scripts/rollback-search-indexes.sql` | 이 스크립트가 만든 인덱스 3개만 제거 |
| `backend/src/test/java/com/team3/backend/search/controller/OpportunitySearchPostgresTest.java` | 검색 결과 회귀와 JIT 설정 범위 테스트(Testcontainers). 성능 측정용 테스트 클래스는 저장소에 없다 |

### 로컬에만 있는 환경 (재현 절차)

API 측정 하네스(`measure.py` 계열)와 회차별 실행 스크립트는 `perf-data/search-performance/`에만 있다.

1. 개발·운영 DB와 분리된 PostgreSQL 16 컨테이너를 만든다(작성자 환경: 포트 5433). 정규화 데이터(공고 140,657건)를 넣고 `ANALYZE`한다. 전역 `jit`는 기본값(`on`)으로 둔다.
2. backend를 실험 DB에만 연결해 `localhost:8081`로 띄운다(`ddl-auto=none`, 스케줄러 비활성).
3. 인덱스 없이 개선 전 상태를 재려면 `OpportunitySearchService.java`만 `40c6618` 버전으로 바꿔 기동한다(이 파일을 바꾼 검색 최적화 커밋은 `4673a55`·`29f3ce2`뿐이다). JIT OFF Before는 JDBC URL 옵션으로 끈다.
4. `database/scripts/search-indexes.sql`을 `application_user`로 적용한 뒤 `search-indexes-check.sql`로 valid·ready를 확인한다.
5. 같은 세션에서 Before → 인덱스 생성 → After → 인덱스 제거 → Before 순서로 잰다. 작성자 스크립트는 `perf-data/search-performance/final-after/run_final.sh`(약 33분), 비교표는 `compare_final.py`, 계획별 인덱스 사용 여부는 `index_usage.py`다.
6. 끝나면 `rollback-search-indexes.sql`로 인덱스를 지우고 코드를 되돌린다.

## 원자료 위치 (`perf-data/search-performance/`, Git 추적 제외)

| 경로 | 내용 | 이 문서의 근거 |
|---|---|---|
| `INDEX.md` | 회차별 측정 조건과 파일 안내 | 전체 |
| `baseline/` | 2차 출발점(2026-10-27). `summary-table.txt`, `attribution.txt`, `sql/exec-times-jit-{on,off}.tsv`, `sql/S3-list.plan.txt`, `sql/db-settings.txt`, 측정 하네스 | 요약·문제·결과 1 |
| `jit-check/` | 3차 JIT ON → OFF → ON API 비교 `compare-out.txt` | 결과 2 |
| `sql-restructure/` | 4차 마감일 정렬 재구성. `sql/explain-summary.txt`, `final-comparison.txt`, `sql/S3-hibernate.log`(서브쿼리·바인딩 수) | 요약·결과 3 |
| `region-filter/` | 5차 지역 필터 유형 분리. `final-comparison.txt`, `sql/type-compare.tsv` | 결과 4 |
| `remaining/` | 6차 `policyVersionInRegion` 적용 전후 `final-comparison.txt`, SQL 대안 후보 `sql/candidate-summary.txt` | 결과 5·기술 선택 이유 |
| `index-exp/` | 7차 인덱스 후보 실험. `candidates.sql`, `sql/index-meta.tsv`, `sql/single-candidate-summary.txt`, `sql/round2-summary.txt`, `sql/plan-BASE-*`, `logs/write-cost.log` | 변경·결과 6·8 |
| `index-verify/` | 8차 검증. `analysis-A.txt`(드리프트·인덱스 조합), `analysis-C.txt`(지문 24개), `logs/write-cost-v2.log`, 스크립트 동작 로그 | 결과 6·정합성 검증·측정 환경 |
| `jit-apply/` | 9차 `SET LOCAL jit = off` 전후 `comparison.txt` (PR #102 본문 표) | 요약·결과 7 |
| `before-jitoff/` | Before(JIT OFF) vs After(JIT OFF) `comparison.txt`, `sql/plan-before-S{3,11}-jit-{on,off}.txt` | 요약·결과 8 |
| `final-after/` | 최종 같은 세션 비교. `README.md`, `final-comparison.txt`, `index-usage.txt`, `run_final.sh` | 요약·측정 환경·결과 6·9·재측정 |
