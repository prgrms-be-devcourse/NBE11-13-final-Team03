# 정규화 적재 쓰기 튜닝

수집 원본 → 정규화(공고·버전·조건 등) 적재의 쓰기 성능 개선 기록과 운영 절차.
측정: `RawIngestBenchmarkTest` (실데이터 141,236건, 홈서버 i9-9880H·PostgreSQL 16 기본 설정, 요청 처리 경로, 3회 중앙값).

## 결과

| 단계 | 변경 (누적) | 첫 적재 | 기준 대비 |
|---|---|---:|---:|
| 기준 | 변경 전 main | 373.1초 | - |
| E2 | 정규화 트랜잭션만 비동기 커밋 (`synchronous_commit=off`) | 302.1초 | −19% |
| E4 | 엔티티 ID 시퀀스(allocationSize 50) + Hibernate batch 100·order_inserts/updates + `reWriteBatchedInserts` + 묶음(100건) 끝 flush | 195.4초 | −48% |
| E3 | 조건 묶음 ID 캐시(커밋 후 반영), 기존 출처 IN 묶음 조회, 처리 상태 batchUpdate | 105.2초 | −72% |
| E3b | 요청 선점 커서 (`q.id > 마지막 선점 ID`) | 88.2초 | −76% |
| E7c | 벤치 결함(빈 표 ANALYZE) 수정 후 재측정 | 77.6초 | −79% |

- 처리 1건당 SQL 11.3회 → 0.4회. 1% 변경 적재(1,412건) 4.2초 → 1.3초.
- WAL은 1건당 약 5.7KB, 체크포인트 횟수와 시간의 상관이 없어 PostgreSQL 설정 튜닝은 효과가 작다고 판단했다.
- 요청 처리 멀티스레드는 측정했지만 운영 t3.small(2 vCPU)에서 이득이 작고 새 조건 묶음 동시 생성 충돌이 생겨 넣지 않았다.

## 설정

| 환경변수 | 기본 | 운영 (데이터 서버) | 설명 |
|---|---|---|---|
| `YON_NORMALIZATION_INGEST_ASYNC_COMMIT` | `false` | `true` | 정규화·요청 선점 트랜잭션에서만 `SET LOCAL synchronous_commit=off`. 수집 원본 저장은 동기 커밋 그대로 |

비동기 커밋의 전제와 영향
- 데이터 서버(data-service)는 1대이고, 꺼지는 대상은 정규화 트랜잭션뿐이다.
- DB 서버가 비정상 종료되면 마지막 수백 ms 동안의 비동기 커밋만 사라진다 (데이터 손상 없음). 정규화 결과·처리 상태·요청 상태는 한 트랜잭션이라 함께 사라지고, 원본은 남아 1시간 대조가 다시 채운다.
- WAL은 순서대로 기록되므로 그 뒤에 어떤 동기 커밋이든 한 번 있었다면 앞선 비동기 커밋도 함께 보존된다. backend가 새 버전을 읽고 동기 커밋으로 추적 행을 남긴 뒤 그 버전만 사라지는 일은 없다.

## 배포 순서 (데이터가 있는 DB)

이 버전은 정규화 엔티티 8개(opportunities, opportunity_versions, opportunity_conditions, opportunity_regions, application_windows, opportunity_sources, training_course_details, youth_policy_details)의 ID를 IDENTITY에서 시퀀스(`<테이블>_seq`, INCREMENT 50)로 바꾼다.

1. **배포 전에** DB 서버에서 `database/scripts/opportunity-id-sequences.sql`을 application_user로 실행한다 (시퀀스를 만들고 최대 ID 뒤로 옮김, 반복 실행 가능).
   빠뜨리면 `ddl-auto: update`가 시퀀스를 1부터 만들어 첫 INSERT부터 기존 ID와 충돌하고 정규화가 멈춘다 (데이터 손상은 없고 같은 SQL로 복구).
2. 데이터 서버 `.env`에 `YON_NORMALIZATION_INGEST_ASYNC_COMMIT=true`.
3. data-service 배포.

새 DB(공고 테이블이 없음)는 data-service 첫 기동 때 Hibernate가 시퀀스를 만들므로 1번이 필요 없다.
Hibernate는 기동할 때 시퀀스 INCREMENT와 allocationSize(50)를 대조하고 다르면 뜨지 않는다. SQL은 50으로 만든다.

## 되돌리기 (IDENTITY 버전으로)

시퀀스 버전이 넣은 행은 IDENTITY 내부 시퀀스(`opportunities_id_seq` 등)를 거치지 않는다. 이전 버전을 띄우기 **전에**
`database/scripts/opportunity-id-identity-rollback.sql`을 application_user로 실행해 각 IDENTITY 시퀀스를 최대 ID 뒤로 맞춘다.

## backend 읽기 엔티티

backend의 공고 읽기 엔티티는 IDENTITY 선언 그대로다. backend는 이 테이블에 쓰지 않고 update도 기존 컬럼을 바꾸지 않아 영향이 없다 (docs/database-boundaries.md).

## 알려진 한계

- 비어 있는 DB의 첫 적재는 autovacuum이 공고 테이블을 처음 ANALYZE하기 전까지(최대 약 1분) 기존 출처 묶음 조회가 느려진다 (E7c: 그 구간 초당 약 900건 → 이후 2,200건). 평소 적재는 통계가 있어 해당 없다.
- 같은 묶음에 같은 (출처, 원본 외부 ID)가 두 번 오면 그 묶음은 원본마다 flush하는 경로로 처리한다. 원본 외부 ID는 다르지만 정규화 키가 같은 드문 경우는 유니크 위반 → 1건씩 재처리로 처리되고 그 묶음만 느려진다.

## 벤치 실행

`RawIngestBenchmarkTest`는 `YON_BENCH_DB_URL`이 있을 때만 돈다. 실데이터는 `YON_INGEST_BENCH_SOURCE_SCHEMA`(원본 복사 스키마), 첫 적재 경로는 `YON_INGEST_BENCH_FIRST_LOAD=queue`, 반복은 `YON_INGEST_BENCH_REPEAT`, 비동기 커밋은 `YON_INGEST_BENCH_ASYNC_COMMIT`.
결과 리포트에 테이블별 SQL 시간과 WAL·체크포인트 증가량이 남는다.
