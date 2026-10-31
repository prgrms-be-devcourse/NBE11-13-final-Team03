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
- 보관 기간은 현재 월을 포함한 4개월(설정값)이다. 오래된 월 파티션은 6절의 자동 정리로만 삭제하며 기본은 꺼져 있다.
  `_old` 보관 중에는 탈퇴 외의 로그 삭제·수정이나 파티션 삭제를 수행하지 않는다(자동 정리도 `_old`가 있으면 건너뛴다).

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

## 6. 보관 정리 (자동, 기본 비활성)

backend의 `UserBehaviorEventRetentionScheduler`가 매월 1일 03:00 KST에 실행한다.
**현재 월 + 직전 3개월(4개월)** 을 남기고, 범위 끝이 보관 시작일 이하인 월 파티션을 `DROP TABLE`로 삭제한다.
예: 2026-11-01 실행 → 2026-08~11 보관, 2026-07 이전 파티션 삭제. 이번 달+12개월까지 없는 월 파티션도 만든다(5절 SQL과 같은 범위).

| 설정 (환경 변수) | 기본 | 의미 |
|---|---|---|
| `yon.behavior.retention.enabled` (`YON_BEHAVIOR_RETENTION_ENABLED`) | false | true일 때만 등록. `app.scheduling.enabled=false`면 꺼짐 |
| `yon.behavior.retention.retained-months` (`YON_BEHAVIOR_RETENTION_RETAINED_MONTHS`) | 4 | 현재 월 포함 보관 월 수, 1 이상 |
| `yon.behavior.retention.dry-run` (`YON_BEHAVIOR_RETENTION_DRY_RUN`) | true | 삭제·생성 대상만 로그로 남김 |
| `yon.behavior.retention.lock-timeout` | 3s | 계획 조회(관리자 미리보기 포함)와 파티션 DDL 하나의 잠금 대기 한도 |

보호 장치:

- 부모가 `RANGE (received_at)` 파티션 테이블이 아니면(일반 테이블·롤백 상태) 아무것도 바꾸지 않는다.
- `user_behavior_events_old`가 있으면(롤백 기간) 아무것도 바꾸지 않는다. 롤백 SQL의 건수 검증이 깨지기 때문이다.
- 이름이 아니라 카탈로그의 실제 범위(`FROM ... TO ...`)로 판정한다. DEFAULT, 해석할 수 없는 범위(MINVALUE 등), 하위 파티션 테이블, 부모에 붙지 않은 테이블은 건드리지 않는다.
  DEFAULT에 행이 있으면 경고만 남긴다(5절 복구 절차).
- advisory lock으로 여러 서버·중복 실행 중 하나만 진행한다. 파티션마다 별도 트랜잭션·`lock_timeout`을 쓰며 실패한 파티션은 남기고 다음 실행에서 다시 판정한다.
- 앱과 DB의 KST 날짜가 하루 넘게 다르면 실행하지 않는다.
- 서버가 03:00에 꺼져 있어 실행을 놓치면 데이터가 한 달 더 남고 다음 달 실행에서 함께 정리된다. 기다리지 않으려면 7절 수동 실행을 쓴다.

경계 예시 (보관 시작일 = 실행 월 1일 - 3개월, 그 이전 시각의 행이 든 파티션만 삭제):

| 실행일 | 보관 | 삭제되는 마지막 시각 | 보관되는 첫 시각 |
|---|---|---|---|
| 2026-11-01 | 2026-08 ~ 11 | 2026-07-31 23:59:59.999999 | 2026-08-01 00:00 (2026-10-31 23:59:59.999999도 보관) |
| 2027-01-01 | 2026-10 ~ 2027-01 | 2026-09-30 23:59:59.999999 | 2026-10-01 00:00 |
| 2027-02-10 | 2026-11 ~ 2027-02 | 2026-10-31 23:59:59.999999 | 2026-11-01 00:00 |
| 2028-05-31 | 2028-02 ~ 05 (윤년) | 2028-01-31 23:59:59.999999 | 2028-02-01 00:00 (2028-02-29 23:59:59.999999도 보관) |

실행 순서와 실패 시 동작 (자동·수동 공통):

1. advisory lock 획득 → 부모·`_old` 확인 → 파티션 목록 한 번 조회. 잠금은 생성·삭제·DEFAULT 점검이 모두 끝날 때까지 유지한다.
   계획 조회(파티션 크기·DEFAULT 행 확인)는 `lock-timeout`과 문장 10초 제한이 걸린 읽기 트랜잭션에서 하며 끝나면 항상 롤백한다.
   다른 연결이 파티션을 `ACCESS EXCLUSIVE`로 잡고 있으면 제한 시간 뒤 오류로 끝나고 생성·삭제를 하나도 하지 않는다
   (advisory lock은 해제, 수동 종료 코드 3). 잠금이 풀린 뒤 다시 실행한다.
2. 미래 월 생성: 월마다 별도 트랜잭션. 실패한 달(DEFAULT에 그 달 행이 있거나 같은 이름의 테이블이 있음)은 롤백되고 나머지 달과 삭제는 계속한다.
   미생성 달의 이벤트는 DEFAULT로 들어가므로 INSERT는 실패하지 않는다. DEFAULT 행은 5절 절차로 옮긴 뒤 다시 실행한다.
3. 오래된 월 삭제: 파티션마다 별도 트랜잭션. 잠금 시간 초과·의존 객체 등으로 실패한 파티션은 데이터와 함께 그대로 남고 다른 파티션 삭제는 계속한다.
4. 이미 커밋된 생성·삭제는 이후 실패로 되돌려지지 않는다. 한 건이라도 실패하면 `COMPLETED_WITH_FAILURES`(WARN 로그, 수동 종료 코드 1)로 기록한다.
5. 다시 실행하면 그 시점의 카탈로그로 다시 판정하므로 실패했던 파티션만 처리된다.

활성화 순서 (운영 DB에 직접 DDL을 실행하지 않는다):

1. 운영 DB에서 1~3절 전환·D+1 점검과 `_old` 삭제가 끝났는지 점검 SQL로 확인한다(`relkind=p`, DEFAULT 0행, `_old` 없음).
2. 삭제될 월의 데이터가 백업에 포함되어 있는지 [백업 문서](db-backup.md)로 확인한다. 삭제된 파티션은 백업 복원으로만 되살릴 수 있다.
3. `.env`에 `YON_BEHAVIOR_RETENTION_ENABLED=true`(dry-run 기본 true)로 배포하고, 다음 1일 03:00 로그
   `Behavior event retention COMPLETED: dryRun=true, keepFrom=..., dropped=[...]`의 대상이 맞는지 확인한다.
4. 팀 합의 후 `YON_BEHAVIOR_RETENTION_DRY_RUN=false`로 바꾸고 재시작한다. 다음 실행 후 점검 SQL로 남은 파티션을 확인한다.

## 7. 수동 정리 (운영자 재실행)

서버 정지로 1일 실행을 놓쳤거나 일부 파티션이 실패했을 때 쓴다. 새 API·서버 없이 **같은 backend 이미지를 웹 서버 없는 일회성 컨테이너**로 띄워
`UserBehaviorEventRetentionManualRun`이 한 번 실행하고 종료한다. 자동 정리 활성화(`YON_BEHAVIOR_RETENTION_ENABLED`)와 무관하지만
보관 개월 수·잠금 시간 설정과 판정·보호 장치(advisory lock, 일반 테이블·`_old`·DEFAULT 보호, 시계 확인)는 자동 실행과 같은 코드를 쓴다.

- 권한: 메인 서버의 배포 폴더와 `.env`(application_user 접속 정보). DDL은 파티션 소유자인 application_user로 실행된다. 운영 DB에 직접 접속하지 않는다.
- 기본은 dry-run이다. 실제 생성·삭제는 `YON_BEHAVIOR_RETENTION_MANUAL_EXECUTE=true`를 줄 때만 한다.
- 웹 서버로 떠 있는 일반 backend에서는 수동 설정이 있어도 실행하지 않는다. 수동 변수는 `.env`에 넣지 않고 명령에만 준다.
- 자동 실행이나 다른 수동 실행이 진행 중이면 잠금을 얻지 못해 아무것도 하지 않고 종료한다(종료 코드 2).

메인 서버(`~/yon`)에서 트래픽이 적은 시간에 실행한다. 다른 backend와 같은 서버에서 JVM이 하나 더 뜨므로 힙을 작게 준다.

```bash
cd ~/yon
# 1) dry-run: 생성·삭제 대상만 로그로 확인
docker compose --env-file .env --env-file deploy.env run --rm --no-deps \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=none -e APP_SCHEDULING_ENABLED=false -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e YON_NORMALIZATION_CONDITION_SET_BACKFILL_ENABLED=false -e JAVA_TOOL_OPTIONS=-Xmx384m \
  -e YON_BEHAVIOR_RETENTION_MANUAL_RUN=true backend
echo "exit=$?"
# 2) 대상이 맞으면 실제 실행: 같은 명령에 -e YON_BEHAVIOR_RETENTION_MANUAL_EXECUTE=true 를 추가한다
```

- 로그: `Behavior event retention started: trigger=manual-dry-run|manual, appDate, dbDate, retainedMonths, dryRun`,
  파티션별 `created`·`dropping ... estimatedRows, bytes`, 요약 `Behavior event retention <상태>: keepFrom, created, dropped, failed, ignored`,
  `finished: elapsedMs`, `exiting with code N`.
- 종료 코드: 0 완료, 1 일부 실패(실패 파티션은 남음, 원인 해결 후 재실행), 2 건너뜀(잠금·일반 테이블·`_old`·시계 불일치), 3 실행 오류.
- 기동 시 DB를 바꾸는 경로는 Hibernate `ddl-auto`(application.yaml과 main()이 항상 붙이는 database-boundary 프로필 모두 `update`)뿐이다.
  Flyway·Liquibase·SQL 초기화는 없고 DB 경계 검증은 읽기 전용이다. 위 명령의 `SPRING_JPA_HIBERNATE_DDL_AUTO=none`은 OS 환경 변수라
  두 설정 파일보다 우선하므로 dry-run·실제 실행 모두 Hibernate가 스키마를 바꾸지 않는다(`RetentionManualDdlPostgresTest`).
  보관 정리 자체의 파티션 구조 검증·생성·삭제는 이 설정과 무관하게 그대로 동작한다. 조건 묶음 백필은 compose가 이미 끄지만 명령에도 명시했다.
- `run --no-deps`는 새 일회성 컨테이너만 만들며 실행 중인 backend·notification-service 컨테이너를 재시작하지 않는다(서비스 간 depends_on도 없다).
  일반 backend의 `ddl-auto` 설정은 바꾸지 않았다.
- 롤백 한계: 삭제된 파티션과 데이터는 되돌릴 수 없고 [백업](db-backup.md) 복원으로만 살릴 수 있다. 실제 실행 전 반드시 dry-run과 백업을 확인한다.
  잘못 만든 빈 미래 파티션은 지워도 되지만, 그 달 데이터가 이미 들어왔다면 DEFAULT 이동 절차(5절)와 같은 검토가 필요하다.
- 이 명령은 로컬 테스트로 동작 경로를 검증했으며, 운영 서버에서 실제로 실행해 확인하지는 않았다. 첫 사용은 dry-run으로 확인한다.

## 8. 관리자 API (관리자 페이지 연동)

실행 중인 backend의 ADMIN API로 미리보기·실행을 한다. 판정·보호 장치·advisory lock은 자동·수동 실행과 같은 코드를 쓴다.
관리자 페이지 프런트엔드는 아직 없으므로 아래 계약을 연동 기준으로 삼는다.

| 설정 (환경 변수) | 기본 | 의미 |
|---|---|---|
| `yon.behavior.retention.admin-execute-enabled` (`YON_BEHAVIOR_RETENTION_ADMIN_EXECUTE_ENABLED`) | false | 실행 API 허용. 미리보기는 설정과 무관하게 읽기 전용으로 동작 |

자동 정리(`enabled`)·dry-run 설정과 별개다. 실행 API는 dry-run 설정과 무관하게 **실제** 생성·삭제를 한다(미리보기가 dry-run 역할).

**인증·인가·CSRF**: 기존 쿠키 인증(access token)과 SPA CSRF(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더)를 그대로 쓴다.
`@PreAuthorize("hasAuthority('ADMIN')")`로 서버에서 검사하며, 실행은 추가로 DB의 현재 역할이 ADMIN이고 현재 `ADMIN_KAKAO_IDS` 목록에 있는지 다시 확인한다
(역할은 로그인 시 승격만 되고 목록에서 빠져도 강등되지 않기 때문이다).

### GET `/api/admin/behavior-events/retention/preview`

DB를 바꾸지 않고 advisory lock도 잡지 않는다. 다른 연결이 파티션을 잠그고 있으면 `lock-timeout` 뒤 500으로 끝난다(잠시 후 다시 조회). 응답 예:

```json
{
  "status": "READY",
  "policy": {"retainedMonths": 4, "futureMonths": 12, "schedule": "매월 1일 03:00 (Asia/Seoul)",
             "schedulerEnabled": false, "schedulerDryRun": true, "adminExecuteEnabled": false},
  "baseDate": "2026-11-01", "keepFrom": "2026-08-01",
  "toDrop": [{"name": "user_behavior_events_2026_07", "from": "2026-07-01T00:00:00", "to": "2026-08-01T00:00:00",
              "estimatedRows": 152672, "totalBytes": 28311552}],
  "toCreate": [{"name": "user_behavior_events_2027_11", "from": "2027-11-01T00:00:00", "to": "2027-12-01T00:00:00", "conflict": false}],
  "ignored": [{"name": "...", "bound": "FOR VALUES FROM (MINVALUE) TO (...)"}],
  "defaultPartitionHasRows": false,
  "planToken": "9f2c..."
}
```

- `status`: `READY` 또는 `SKIPPED_NOT_PARTITIONED`(일반 테이블)·`SKIPPED_ROLLBACK_WINDOW`(`_old` 있음)·`SKIPPED_CLOCK_MISMATCH`. READY가 아니면 `planToken`은 null이고 실행 버튼을 막는다.
- `estimatedRows`는 PostgreSQL 통계 추정치(정확한 COUNT 아님, ANALYZE 전이면 -1 또는 0), `totalBytes`는 인덱스 포함 크기다.
- `ignored`(해석할 수 없는 범위)와 `defaultPartitionHasRows=true`는 삭제되지 않으며 운영 확인이 필요하다는 경고로 표시한다.

### POST `/api/admin/behavior-events/retention/execute`

본문 `{"planToken": "<미리보기의 planToken>"}`. 서버는 advisory lock을 잡고 계획을 다시 만든 뒤 토큰이 같을 때만 실행한다.
파티션 이름·SQL은 받지 않는다.

| 응답 | 의미 | 화면 처리 |
|---|---|---|
| 200 `status=COMPLETED` | 생성·삭제 완료 (`created`, `dropped`) | 결과 표시 후 미리보기 새로고침 |
| 200 `status=COMPLETED_WITH_FAILURES` | 일부 실패 (`failed`: "파티션: 원인"), 성공분은 유지 | 실패 목록 표시, 원인 해결 후 다시 미리보기 → 실행 |
| 400 `COMMON_001` | planToken 없음 | 미리보기부터 다시 |
| 401 | 로그인 필요 | 로그인 |
| 403 `RETENTION_001` | 관리자 아님(또는 현재 관리자 목록에 없음) | 권한 안내 |
| 403 `RETENTION_002` | 실행 API 비활성화 | 미리보기만 제공 |
| 403 (CSRF) | CSRF 토큰 없음·불일치 | 기존 apiClient의 CSRF 처리 |
| 409 `RETENTION_003` | 미리보기 이후 기준 월·대상이 바뀜 | 다시 미리보기 후 확인 |
| 409 `RETENTION_004` | 자동·수동·다른 관리자 실행이 진행 중 | 잠시 후 다시 미리보기 |
| 409 `RETENTION_005` | 일반 테이블·`_old`·시계 불일치로 실행 불가 | 미리보기 상태 표시 |
| 500 | 계획 조회가 파티션 잠금 대기 한도를 넘음 등 실행 오류. 아무것도 바꾸지 않음 | 잠시 후 다시 미리보기 |

권장 화면 흐름: 미리보기 표시 → 삭제 대상·보관 시작일을 보여 주는 확인 대화상자 → 실행 → 결과 표시와 미리보기 새로고침.
감사 로그는 `action=BEHAVIOR_RETENTION_PREVIEW|EXECUTE adminUserId=… result=…`로 남는다(관리자 내부 ID만, 토큰·개인정보 없음).

활성화 순서: 6절 1~2단계(운영 파티션·`_old`·백업 확인) → 관리자 계정으로 미리보기 확인 → 팀 합의 후
`.env`에 `YON_BEHAVIOR_RETENTION_ADMIN_EXECUTE_ENABLED=true`로 재배포한다.

## 검증

`RetentionAdminApiPostgresTest`는 실제 보안 필터·메서드 보안·CSRF로 비로그인 401, USER 403, 목록에서 빠진 ADMIN의 실행 403,
CSRF 누락·불일치 403, 실행 비활성 403, 미리보기의 무변경·무잠금과 4개월 계획, 토큰 일치 실행, 위조·월 변경·대상 변경 시 409,
진행 중 실행과 `_old` 차단, 비정상 파티션 보호, 부분 실패 후 재실행, 감사 로그,
파티션·DEFAULT가 외부에서 `ACCESS EXCLUSIVE`로 잠겼을 때 미리보기·실행의 제한 시간 내 종료와 advisory lock·풀 연결 상태 정상, 해제 후 재실행을 검증한다.
`RetentionManualDdlPostgresTest`는 7절 명령과 같은 OS 환경 변수로 기동한 dry-run·실제 실행이 Hibernate 스키마를 바꾸지 않고
일반 기동은 기존처럼 update로 동작함을 검증한다.

`UserBehaviorEventRetentionPostgresTest`는 PostgreSQL 16 폐기 DB에서 공식 전환 SQL을 적용한 뒤 4개월 보관,
6절 경계 예시의 행 단위 삭제·보관(연도 변경·윤년 포함), 반복·dry-run, 잠금 시간 초과·의존 객체로 인한 삭제 실패와 재실행,
계획 조회 잠금 시간 초과(실행·dry-run·계획) 후 같은 연결의 상태 정상과 재실행,
미래 월 생성 실패 시 삭제 지속·실패 보고·DEFAULT INSERT·복구, advisory lock 충돌과 자동·수동 동시 실행,
수동 dry-run·실제 실행과 자동 실행의 판정 일치, `_old`·DEFAULT·분리된 테이블·MINVALUE 범위·일반 테이블 보호,
웹 서버 없는 일회성 기동, 새 월 INSERT 라우팅과 회원 탈퇴 삭제를 검증한다.
`UserBehaviorEventRetentionSchedulerTest`는 자동·수동 실행의 등록 조건(서로 독립), 기본값(4개월·dry-run), 설정 검증,
웹 backend 안에서의 수동 실행 거부, 종료 코드를 확인한다.

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
