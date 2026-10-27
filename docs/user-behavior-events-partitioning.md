# 사용자 행동 로그 월 파티셔닝

## 범위와 배포 순서

`application.user_behavior_events`만 received_at 월별 RANGE 파티션으로 전환한다.
원본 스냅샷 파티셔닝 PR #86과 독립적으로 적용할 수 있다. 수집·정제·검색·추천·알림 계약은 변경하지 않는다.

**backend 설정 PR 병합·배포 → backend 중지 → 전환 SQL → 점검 → backend 재기동** 순서다.
이 PR의 런타임 변경은 Hibernate의 `PARTITIONED TABLE` 인식 설정 한 줄이다.
일반 테이블에서도 동작하므로 SQL 전에 먼저 배포한다. SQL은 기동 시 자동 실행되지 않는다.

## 데이터 계약

- 부모 이름과 컬럼·인덱스·외래키·received_at(timestamp without time zone)을 보존한다.
- PK는 `(id, received_at)`이다. 기존 API/JPA는 id를 사용하므로 부모의 공통 IDENTITY 시퀀스로
  전체 월에서 유일한 ID를 생성한다. 직접 ID 입력이나 월별 시퀀스를 사용하지 않는다.
- 기존 첫 달부터 기존 마지막 달과 KST 이번 달+12개월 중 더 늦은 달까지 만든다. 빈 테이블도 전환한다.
- received_at은 기존 앱의 서버 수신 시각을 그대로 보존한다. 앱 서버 TZ=Asia/Seoul을 확인한다.
  DB 세션 시간대를 바꿔 기존 값을 변환하지 않는다. 운영 예시는 deploy/main/.env.example을 따른다.
- 이벤트는 반복 기록을 허용하며 기존 기록을 수정하는 기능은 없다. 회원 탈퇴 시 삭제는 예외다.
- users FK의 ON DELETE CASCADE와 opportunities FK의 삭제 제한을 모두 복사한다.
  `_old`도 같은 FK를 유지하므로 탈퇴 시 두 복사본에서 모두 삭제돼 롤백해도 탈퇴 로그가 복원되지 않는다.
- ID 단독으로 행동 로그를 참조하는 예상 밖 FK가 있으면 전환을 중단한다.
- 별도 보관 기간·파티션 삭제 스케줄은 추가하지 않는다. 보관 정책은 팀에서 결정한다.
  `_old` 보관 중에는 탈퇴 외의 로그 삭제·수정이나 파티션 삭제를 수행하지 않는다.

## 1. 전환 준비

backend 설정이 배포됐는지 확인하고 사용자 요청이 적은 시간대를 선택한다.
DB 서버에서 로그 크기·파티션 복사·인덱스·WAL을 수용할 여유 공간과 복원 가능한 백업을 확인한다.

```sql
SELECT pg_size_pretty(pg_total_relation_size('application.user_behavior_events'));
SELECT min(received_at), max(received_at), count(*) FROM application.user_behavior_events;
```

```bash
cd ~/yon-db
docker compose exec -T postgres-db pg_dump -U postgres -d yon -Fc > ~/yon-before-behavior-partition.dump
docker compose exec -T postgres-db pg_restore -l < ~/yon-before-behavior-partition.dump
```

로컬 저장소에서 SQL 네 파일을 DB 서버에 복사한다.

```bash
scp database/scripts/user-behavior-events-partition.sql \
    database/scripts/user-behavior-events-partition-check.sql \
    database/scripts/user-behavior-events-add-partitions.sql \
    database/scripts/rollback-user-behavior-events-partition.sql ubuntu@<DB서버>:~/yon-db/sql/
```

## 2. backend 중지 → 전환 → 확인

메인 서버:

```bash
cd ~/yon
docker compose --env-file .env --env-file deploy.env stop backend
```

DB 서버:

```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; cat sql/user-behavior-events-partition.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
{ echo 'SET ROLE application_user;'; cat sql/user-behavior-events-partition-check.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

오류 시 중단한다. 미커밋 전환은 연결 종료 시 롤백된다. 잠금 획득 제한은 5초다.
확인: relkind=p·RANGE(received_at), 전환 직후 old/new 전체 값·건수 일치,
DEFAULT=0행, 월별 파티션의 소유자 application_user, PK·두 인덱스·FK 삭제 규칙 보존,
received_at 타입 유지, 중복 ID=0, 다음 시퀀스 값>최대 ID.
점검 SQL은 결과를 보여 주며 기대값을 자동 판정하지 않는다.

메인 서버:

```bash
cd ~/yon
docker compose --env-file .env --env-file deploy.env up -d backend
docker compose --env-file .env --env-file deploy.env logs --since 5m backend
```

기동 오류·Hibernate DDL 오류가 없어야 한다. 인증 사용자의 공고 조회 행동과 저장·숨김 기록을 확인한다.
저장·숨김은 상태가 실제 전환될 때만 로그가 생긴다. 배포 기동 확인만으로 이벤트 저장 검증이 되지는 않는다.

## 3. 다음 날 확인과 롤백 종료

점검 SQL을 다시 실행하고 새 이벤트의 월 라우팅·DEFAULT 0행·오류 로그를 확인한다.
전환 후에는 old/new 건수가 달라지는 것이 정상이다. 탈퇴 사용자의 로그가 `_old`에도 남지 않는지 확인한다.
다음 날 검증과 롤백 종료 합의 후에만 `_old`를 삭제한다. 이후 제공된 롤백 SQL은 사용할 수 없다.

```sql
SET ROLE application_user;
DROP TABLE application.user_behavior_events_old;
```

## 4. 롤백

backend 중지 후 DB 서버에서 실행한다. `_old`가 남아 있어야 한다.
새 이벤트를 복사하고 기존 PK·인덱스·시퀀스 이름으로 되돌린다.
추가된 설정은 일반 테이블에서도 동작하므로 backend 이미지 롤백은 필요 없다.

```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; cat sql/rollback-user-behavior-events-partition.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

relkind=r, 새 이벤트·ID·received_at 보존, 탈퇴 사용자 로그 부재를 확인하고 같은 backend를 다시 올린다.
정상 확인 후 `DROP TABLE application.user_behavior_events_p`를 별도 실행한다.
재전환은 같은 전환 SQL로 가능하다.

## 5. 월 점검과 파티션 추가

매월 점검 SQL로 DEFAULT 0행, 마지막 월이 이번 달+6개월 이상 남았는지, 파티션 크기를 확인한다.
여유가 6개월 미만이면 아래 SQL로 KST 이번 달+12개월까지 추가한다. 기존 파티션은 건너뛴다.
짧게 부모 잠금을 잡으므로 트래픽이 적은 시간에 수행한다.

```bash
cd ~/yon-db
{ echo 'SET ROLE application_user;'; cat sql/user-behavior-events-add-partitions.sql; } \
  | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

DEFAULT에 추가하려는 월의 행이 있으면 추가 SQL 전체가 실패한다. backend를 중지하고 백업 후,
해당 행들이 이번 달~+12개월에 속하는지 확인한 경우에만 아래 복구 절차를 쓴다.
그 범위 밖의 행은 기간을 확장하는 별도 이전 SQL을 먼저 검토한다.
행동 로그를 참조하는 새 FK가 생겼다면 이 삭제·재삽입 방식은 다시 검토해야 한다.

DB 서버의 `~/yon-db`에서 실행한다. 한 psql 연결·트랜잭션에서 보관·이동하므로 실패 시 데이터가 롤백된다.

```bash
set -euo pipefail
{
  echo "SET ROLE application_user; BEGIN; SET LOCAL lock_timeout = '5s'; SET LOCAL search_path TO application, pg_catalog;"
  echo 'LOCK TABLE user_behavior_events IN ACCESS EXCLUSIVE MODE;'
  echo 'CREATE TEMP TABLE behavior_move ON COMMIT DROP AS SELECT * FROM user_behavior_events_default;'
  echo 'DELETE FROM user_behavior_events_default;'
  sed '/^BEGIN;$/d; /^COMMIT;$/d' sql/user-behavior-events-add-partitions.sql
  echo 'INSERT INTO user_behavior_events SELECT * FROM behavior_move; COMMIT;'
} | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
```

점검 SQL로 전체 데이터 보존·DEFAULT=0을 확인하고 backend를 다시 올린다.

## 검증

`UserBehaviorPartitionPostgresTest`는 PostgreSQL 16 폐기 DB에서 application_user 소유권과
실제 backend의 Hibernate update 재기동(DDL 오류 즉시 실패)을 사용한다.
기존 월 경계 데이터 보존, 인증 행동 API·저장/숨김, 반복 기록, JPA ID 조회,
사용자 탈퇴 시 양쪽 복사본 삭제와 공고 보존, DEFAULT 유입·추가 실패·복구·반복 추가,
롤백·재전환·ID 생성, 빈 테이블과 예상 밖 참조 FK의 원자적 중단을 검증한다.

### 로컬 실행 결과 (2026-10-27)

- Java 25.0.3·Docker·PostgreSQL 16 폐기 DB로 확인했다. 운영 DB는 변경하지 않았다.
- backend `test bootJar`: 980개 중 978개 통과, 실패·오류 0. 성능 전용 벤치마크 2개는 미실행했다.
- 기존 PostgreSQL 조건부 테스트는 별도 폐기 DB의 YON_TEST_DB_URL을 주입해 실행했다.
- UserBehaviorPartitionPostgresTest의 전환·롤백·재전환 및 빈 테이블/참조 FK 검증 2개가 모두 실행·통과했다.
- 실행 JAR 빌드, CI의 실행 여부 검사, 운영 문서의 셸 문법 검사, git diff --check가 통과했다.
- 운영 SQL 적용·실제 배포·다음 날 데이터 확인은 별도 수행한다.
