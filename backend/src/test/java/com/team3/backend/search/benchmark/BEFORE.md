# 공고 상세 조회 Before 기준선

측정일: 2026-10-21. 대상: `GET /api/opportunities/{opportunityId}`의 현재 구현. 운영 DB·외부 API는 사용하지 않았다.

## 환경과 방법

- Windows 11, OpenJDK 25.0.3, Spring Boot 4.1.1, Testcontainers `postgres:16-alpine` (실제 서버 PostgreSQL 16.14).
- 공고/버전 각 2개(청년정책·직업훈련 각 1개), 신청기간 2개, 조건 1개, 지역 1개, 공고지역 2개, 유형별 상세 각 1개, 사용자/판정 프로필 각 3개, 판정 결과 4개(최신 2·만료 2). 미판정 사용자는 판정 결과가 없다.
- 수집 스케줄러 비활성화. 단일 스레드 MockMvc 요청으로 시나리오당 20회 워밍업 후 100회 측정하며 8개 시나리오를 교차 실행했다. 테스트 데이터 INSERT와 판정 사전 저장은 측정 밖에서 완료했다.
- 응답 시간은 `MockMvc.perform` 시작부터 `andReturn` 완료까지다. SQL 시간은 JDBC `PreparedStatement.execute*` 내부의 클라이언트 측 경과시간 합계로, **PostgreSQL 서버 전용 실행시간이 아니다**. SQL 개수는 Hibernate `Statistics`의 준비된 명령 수와 JDBC 계측 수가 일치하는지 검증했다. 요청 구간에서 SELECT 외 SQL은 0건이었다.
- `findFreshEligibility()` 앞뒤는 테스트용 spy로 구분했다. 테스트용 JDBC 프록시와 spy의 비용이 응답 시간에 포함된다. HTTP 네트워크 왕복·TLS·실제 운영 데이터량은 포함되지 않는다.

## 측정값

단위: 시간 ms. `추가`는 `findFreshEligibility()` 내부 SQL 수이고, 로그인 요청에는 이와 별개로 저장 여부 조회 1회가 포함된다. `중복`은 최신 판정에서 공고·신청기간·훈련일정 projection 3회의 JDBC 시간이다.

| 공고 | 시나리오 | 전체 SQL | 추가 SQL | 평균 응답 | p50 | p95 | p99 | SQL 합계 평균 | Provider SQL 평균 | 중복 SQL 평균 | 중복/응답 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 정책 | A 비로그인 | 5 | 0 | 18.797 | 18.274 | 28.487 | 34.534 | 10.011 | 0 | 0 | 0% |
| 정책 | B 로그인·최신 | 10 | 4 | 37.073 | 34.878 | 50.295 | 67.655 | 21.186 | 8.644 | 6.297 | 16.984% |
| 정책 | C 로그인·미판정 | 7 | 1 | 25.683 | 24.126 | 39.647 | 53.804 | 14.659 | 2.175 | 0 | 0% |
| 정책 | D 로그인·만료 | 7 | 1 | 25.660 | 23.683 | 39.026 | 50.050 | 14.405 | 2.135 | 0 | 0% |
| 훈련 | A 비로그인 | 6 | 0 | 21.138 | 18.814 | 36.059 | 53.583 | 12.332 | 0 | 0 | 0% |
| 훈련 | B 로그인·최신 | 11 | 4 | 37.908 | 35.868 | 54.527 | 84.859 | 22.004 | 8.204 | 6.194 | 16.340% |
| 훈련 | C 로그인·미판정 | 8 | 1 | 27.998 | 25.963 | 43.317 | 61.705 | 16.325 | 2.120 | 0 | 0% |
| 훈련 | D 로그인·만료 | 8 | 1 | 28.965 | 27.249 | 47.781 | 52.782 | 17.078 | 2.298 | 0 | 0% |

## SQL 경로와 해석

`OpportunityDetailService.get()`는 공고, 신청기간, 조건, 지역, 유형별 상세를 읽는다. 훈련은 지역 검증을 위한 현재 버전 스냅샷을 추가로 읽어 기본 6회다. 로그인하면 `PreferenceService.getSavedOpportunityIds()`의 저장 여부 SELECT가 1회 추가된다.

`LocalEligibilityProvider.findFreshEligibility()`의 `EligibilityResultRepository.findFresh()`는 현재 공고 버전·프로필 버전·규칙 버전·만료일을 확인하는 SELECT 1회다. 최신 결과가 있을 때만 `OpportunityInfoAssembler.assemble()`이 `findInfoRows`, `findRowsByVersionIdIn`, `findScheduleRows`를 각각 실행한다. 상세 조회가 이미 공고·신청기간·유형별 상세를 읽었지만, 이 세 projection SQL은 실제로 다시 DB에 갔다. 청년정책에서는 훈련일정 조회가 빈 결과여도 실행됐다. 이는 **완전히 같은 SQL 문장이 아닌 중복 데이터 접근**이다. Hibernate 1차 캐시는 엔티티를 보유해도 별도 projection 쿼리 실행은 막지 않았다. 미판정·만료 결과에서는 조립이 호출되지 않았다.

최신 결과 시 중복 접근의 JDBC 시간이 평균 응답 시간 중 정책 16.984%, 훈련 16.340%였다. 이는 관측된 구간 비율이지 제거 시 개선율이 아니다. A와 B의 응답 차이에도 판정 결과 조회, 저장 여부 조회, DTO 매핑, 인증 처리 등이 포함되므로 이를 모두 중복 쿼리 탓으로 돌릴 수 없다.

## 비교와 재측정

같은 코드·JDK·컨테이너 이미지·워밍업/반복 수·픽스처·MockMvc 경로로 After를 측정해야 한다. 기본 Gradle 테스트에서는 환경변수가 없으므로 이 클래스가 자동으로 건너뛰어진다. PowerShell에서 Docker Desktop 실행 후:

```powershell
$env:YON_DETAIL_BEFORE_BENCH='true'
.\gradlew.bat test --tests '*OpportunityDetailBeforeBenchmarkTest' --console=plain
```

선택적으로 `YON_DETAIL_BENCH_WARMUP`, `YON_DETAIL_BENCH_ITERATIONS`를 양의 정수로 설정할 수 있다. 원자료(각 시나리오 첫 요청의 SQL 전문 포함)는 `build/reports/opportunity-detail-before/raw.md`에 생성된다. 이 문서의 수치는 위 기본값으로 실행한 결과다.

작은 고정 픽스처, 로컬 Docker/Windows 스케줄링, JIT·GC, OS 캐시 및 테스트용 spy/JDBC 계측 오버헤드 때문에 운영 지연이나 인과적 개선폭을 대표하지 않는다. GC/JIT 횟수 및 서버 측 `EXPLAIN ANALYZE` 시간은 따로 측정하지 않았다. 개선 후보는 정책 엔진이 상세 화면에 이미 있는 공고 요약을 재사용하거나 읽기 전용 판정 DTO를 제공하는 방식이지만, 도메인 간 계약과 결합도가 늘어난다. 다른 환경에서 이 비용이 작다면 현 구조 유지도 합리적이다.
