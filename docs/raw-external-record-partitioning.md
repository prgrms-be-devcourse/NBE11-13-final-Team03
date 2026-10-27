# 원본 스냅샷 출처 파티셔닝

## 범위와 병합 조건

`raw_external_record`만 `source_id` LIST 파티션으로 전환한다. 행동 로그 파티셔닝은 별도 작업이다.
부모 테이블명·원본 ID·API·SQS 계약은 유지한다. backend·검색·추천·알림 코드는 변경하지 않는다.

**기존 DB는 수집·정제를 중지하고 전환 SQL을 적용한 뒤 이 PR을 병합·배포한다.**
main 병합은 data-service 자동 배포를 시작하므로 SQL 적용 전에는 Draft를 유지한다.
새 엔티티·정제 조회에는 `raw_normalization_request.source_id`가 필요하고,
전환 뒤 옛 앱은 이 NOT NULL 컬럼을 채우지 못한다. 구·신 버전을 동시에 실행하지 않는다.

새 빈 DB는 Hibernate가 일반 테이블과 source_id를 만든다. 먼저 파티셔닝된 상태로 자동 생성되지는 않는다.
수집 활성화 전에 서비스 중지 → 전환 SQL → 재시작 절차를 별도로 수행한다.

## 코드와 데이터 계약

- 정제 요청은 원본의 source_id를 저장한다. 기존 요청은 SQL로 원본을 따라 백필한다.
- 요청 FK는 `(raw_record_id, source_id)`와 `(previous_raw_record_id, source_id)`이다.
- 정제 요청 조회는 원본 ID·출처를 함께 조인한다. 전체 출처를 순회하는 대조는 유지한다.
- 모든 파티션의 ID는 부모의 **공통 IDENTITY 시퀀스**에서 생성한다.
  JPA·원본 조회 API·정제 커서는 기존 단일 ID를 사용하므로 ID가 전체 출처에서 유일해야 한다.
  복합 PK 자체는 출처 간 같은 ID를 막지 못한다. 직접 ID 삽입·파티션별 ID 생성은 허용하지 않는다.
- 기존 원본을 삭제하거나 payload/source_id를 수정하는 작업은 이번 전환에 포함하지 않는다.
  롤백은 새 원본과 last_seen_at 갱신을 복원하는 현재의 스냅샷 계약을 전제로 한다.
- LISTEN/NOTIFY는 정제 요청 ID를 전달한다. 요청 테이블과 트리거는 유지한다.
- data-service의 `hibernate.hbm2ddl.extra_physical_table_types: PARTITIONED TABLE`은 부모 테이블 인식을 위한 설정이다.
  backend는 원본 엔티티가 없어 이번 전환에서 해당 설정을 추가하지 않는다.
- main에 반영된 #81 정제 튜닝의 요청 커서·묶음 쓰기·JDBC 설정을 유지하며 원본 ID·출처 조인을 함께 사용한다.

AWS 담당자의 실행 순서·서버별 명령·인계 지점은 [AWS 작업 순서](raw-partition-aws-runbook.md)를 따른다.

## 1. 사전 준비

수집 스케줄(09:30 KST)과 겹치지 않는 저트래픽 시간에 진행한다. 진행 중인 수집이 없어야 한다.
DB의 원본 총크기와 디스크 여유를 확인한다. 전환 중 새 테이블·인덱스와 `_old`가 공존한다.
최소 원본 전체 크기 2배의 여유를 확보하되 실제 인덱스·WAL 증가도 고려한다.
DB 서버에서 백업하고 복원 가능한 파일인지 확인한다.

```bash
cd ~/yon-db
docker compose exec -T postgres-db pg_dump -U postgres -d yon -Fc > ~/yon-before-raw-partition.dump
docker compose exec -T postgres-db pg_restore -l < ~/yon-before-raw-partition.dump
```

```sql
SELECT id, source_id, status FROM application.sync_history WHERE status IN ('PENDING', 'RUNNING');
SELECT pg_size_pretty(pg_total_relation_size('application.raw_external_record'));
```

PR 브랜치에서 SQL 세 파일을 DB 서버 `~/yon-db/sql/`에 복사한다.

```bash
scp database/scripts/raw-external-record-partition.sql \
    database/scripts/raw-external-record-partition-check.sql \
    database/scripts/rollback-raw-external-record-partition.sql ubuntu@<DB서버>:~/yon-db/sql/
```

## 2. 중지 → SQL → 병합·배포

데이터 서버에서 실행한다. 운영 중인 정상 이미지의 deploy.env를 별도 보관한다.
backend는 계속 실행하되 날짜 추적 작업과 DB 잠금이 겹치지 않는 시간에 수행한다.
전환 트랜잭션은 원본·정제 요청·출처를 잠그므로 이 테이블을 읽는 backend 요청도 잠시 대기할 수 있다.

```bash
cd ~/yon
cp deploy.env deploy.env.before-raw-partition
docker compose --env-file .env --env-file deploy.env stop data-service
```

DB 서버에서 application_user 소유로 실행한다. 오류이면 중단하고 원인을 확인한다.
psql 연결 종료 시 미커밋 트랜잭션은 롤백된다. 잠금 획득 제한은 5초이며 실패 시 재시도 시간을 합의한다.

```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; cat sql/raw-external-record-partition.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
{ echo 'SET ROLE application_user;'; cat sql/raw-external-record-partition-check.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

확인: 부모 relkind=p·LIST(source_id), 출처 파티션·DEFAULT, DEFAULT=0행,
전환 직후 old/new 건수 일치, source_id NULL=0, 복합 FK 2개, 중복 ID=0,
다음 시퀀스 값>최대 ID, 소유자 application_user, NOTIFY 트리거 활성.
점검 SQL은 결과를 보여 주는 읽기 도구이며 기대값을 자동 판정하지 않는다.

그다음 Draft 해제·병합 → Data service 이미지 게시 → Deploy 완료를 확인한다.
기동 로그의 `Started DataServiceApplication`, DDL 실패·FK 오류 부재를 확인한다.
관리자 API에서 기존 원본 조회와 수동 수집을 수행해 신규·변경·동일 데이터 저장,
정제 요청의 source_id·상태 처리 및 backend 날짜 추적 조회를 확인한다.
배포 워크플로의 RPC 조회 성공만으로 정제 처리를 확인할 수는 없다.

**자동 이미지 롤백은 DB를 되돌리지 않는다.** 전환 후 배포 실패로 옛 이미지가 올라오면
이를 중지하고 아래 SQL 롤백과 이미지 복구를 함께 수행한다.

## 3. 다음 날 확인

09:30 정기 수집과 정제 완료를 확인한 뒤 점검 SQL을 다시 실행한다.
서비스 재개 뒤 `_old`와 새 테이블 건수가 달라지는 것은 정상이다.
KST의 날짜 경계를 명시해 오늘 생성한 원본과 요청을 확인한다.

```sql
SELECT source_id, status, new_count, updated_count, unchanged_count, failed_count
FROM application.sync_history
WHERE started_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul';
SELECT tableoid::regclass, count(*) FROM application.raw_external_record
WHERE first_seen_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul' GROUP BY 1;
SELECT status, count(*) FILTER (WHERE source_id IS NULL) AS null_source, count(*)
FROM application.raw_normalization_request
WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul' GROUP BY status;
```

정상 수집·정제·라우팅과 롤백 종료 합의를 확인한 뒤에만 `_old`를 삭제한다.
삭제 후에는 제공된 롤백 SQL을 사용할 수 없다.

```sql
SET ROLE application_user;
DROP TABLE application.raw_external_record_old;
```

## 4. 롤백

data-service를 중지하고 DB 서버에서 실행한다. `_old`가 있어야 한다.

```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; cat sql/rollback-raw-external-record-partition.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

부모가 일반 테이블(relkind=r)로 돌아왔는지, 새로 들어온 원본·last_seen_at·요청이 보존됐는지 확인한다.
데이터 서버에서 보관한 **전환 전 이미지**로 올린다.

```bash
cd ~/yon
cp deploy.env.before-raw-partition deploy.env
docker compose --env-file .env --env-file deploy.env up -d data-service
```

이 PR을 이미 병합했다면 revert PR을 준비해 다음 배포가 전환 전 코드로 나가게 한다.
정상 복구 확인 후 DB 서버에서 `DROP TABLE application.raw_external_record_p`를 별도 실행한다.
재전환은 같은 전환 SQL로 가능하다. 남아 있는 source_id의 NULL도 다시 백필한다.

## 5. 새 출처

external_source 등록 후 수집 활성화 전에 해당 source_id의 파티션을 만든다.

```sql
CREATE TABLE application.raw_external_record_<code> PARTITION OF application.raw_external_record
FOR VALUES IN (<source_id>) WITH (fillfactor = 70);
```

DEFAULT에 해당 출처 행이 이미 있으면 단순 CREATE PARTITION은 실패한다.
수집을 중지·백업하고 원본 및 정제 요청 FK를 보존하는 별도 이전 SQL을 검토한 뒤 수행한다.
FK가 존재하므로 원본을 DELETE하는 단순 절차를 실행하지 않는다. DEFAULT 유입을 점검해 원인을 해결한다.

## 검증 범위

`RawPartitionPostgresTest`는 PostgreSQL 16 폐기 DB에서 application_user 소유로 전환·롤백·재전환하고,
실제 data-service를 update+DDL 오류 즉시 실패 설정으로 재기동한다.
두 출처·동일 외부 ID, 기존 ID 조회, 동일 원본 갱신, 변경 스냅샷·요청 저장,
출처/이전 원본의 복합 FK, 정제 선점·대조 SQL, 트리거 유지, 롤백 후 옛 INSERT와 재전환을 확인한다.
실제 운영 DB의 백업·전환·배포·D+1 검증은 위 절차에 따라 별도로 수행한다.

### 로컬 실행 결과 (2026-10-27)

- Java 25.0.3, Docker, PostgreSQL 16 폐기 DB로 검증했다. 운영 DB는 변경하지 않았다.
- data-service `test --rerun bootJar`: 394개 실행, 실패 0, 오류 0, 건너뜀 0; 실행 JAR 빌드 성공.
- 조건부 정제 테스트는 전용 `YON_TEST_DB_URL`을 주입했다.
- 벤치마크는 별도 `YON_BENCH_DB_URL`에서 원본 100건·트랜잭션 크기 100·청년정책 50%로
  큐 기반 첫 적재·무변경·1% 변경 SQL의 동작만 확인했다. 성능 개선 수치로 해석하지 않는다.
- 최신 main의 정제 튜닝을 반영한 뒤 394개 전체 테스트를 다시 통과했고, 배포·롤백 셸 모의 테스트 5개도 Linux 컨테이너에서 통과했다.
- CI의 테스트 실행 여부 검사도 로컬 결과 XML로 통과했다.
- backend 전체 회귀 테스트와 운영 D+1 검증은 로컬에서 실행하지 않았다.
