# AWS 담당자 작업 순서 — 원본 스냅샷 파티셔닝 (#86)

> **진행 상태: 실행 준비용 문서입니다. 운영 DB에는 아직 적용하지 않았습니다.**
> 핵심 순서: 수집·정제 중지 → DB 전환·점검 → 코드 담당에게 인계 → #86 병합·자동 배포 → 동작 확인.
> DB 전환부터 새 data-service 배포 완료까지 서비스 중지를 유지합니다.

## 1. 작업 범위와 담당

| 담당 | 실행 위치 | 해야 할 일 |
| --- | --- | --- |
| 코드 담당 | GitHub / 로컬 | #86 충돌·테스트·리뷰 완료, 검증된 SQL 세 파일 전달, DB 점검 완료 통보 후 병합 |
| AWS 담당 | 데이터 서버 `~/yon` | 전환 전 이미지 정보 보관, data-service 중지, 배포·복구 확인 |
| AWS 담당 | DB 서버 `~/yon-db` | 백업, 전환 SQL·점검 SQL 실행, 필요 시 롤백 |
| 코드 담당 + AWS 담당 | 관리자 API / 로그 / DB | 기존 원본 조회·수동 수집·정제와 다음 날 정기 수집 확인 |

- 대상: `application.raw_external_record`와 정제 요청의 source_id/FK. 행동 로그(#87)는 별도 작업입니다.
- backend·알림 서버는 이 작업 때문에 중지하지 않습니다. 원본을 읽는 관리자·날짜 추적 조회는 DB 잠금 동안 대기할 수 있습니다.
- 서버 주소·SSH 접속법·실제 DB명은 AWS 담당자가 확인합니다. 아래 명령은 현재 저장소의 DB `yon`, 컨테이너 `postgres-db` 기준입니다.
- 추가되는 SQL은 **앱 기동 시 자동 실행되지 않습니다.** GitHub 병합만으로 DB가 전환되지 않습니다.

## 2. 작업 전 함께 확인

- [ ] #86이 최신 main과 충돌 없이 테스트·리뷰를 통과했다. 실행할 PR 커밋과 SQL 파일을 고정했다.
- [ ] 데이터 서버에서 현재 배포된 data-service의 수집·정제가 정상이다.
- [ ] main에 반영된 #81의 정규화 ID 시퀀스 준비가 운영에도 반영돼 있다. 미완료면 담당자와 별도 선행 작업으로 완료한다. 이 문서가 시퀀스 마이그레이션을 대신하지 않는다.
- [ ] 09:30 KST 정기 수집과 겹치지 않는 저트래픽 시간에 작업한다.
- [ ] 작업 중 수동 수집 요청·다른 PR의 main 병합·수동 배포를 하지 않도록 팀에 공지했다.
- [ ] 디스크 여유가 원본 전체 크기 2배 이상인지 확인하고, 인덱스·WAL 여유도 확보했다.
- [ ] SQL 전환 결과를 확인할 AWS 담당자와 병합할 코드 담당자가 동시에 대응할 수 있다.

이전 버전은 새 source_id NOT NULL을 채우지 못하고, 새 코드는 그 컬럼을 필요로 합니다.
**SQL 적용 전에 #86을 병합하거나, SQL 적용 후 옛 data-service를 그대로 올리면 안 됩니다.**

## 3. 전달 파일 준비 — 코드 담당 → AWS 담당

다음 세 파일을 **같은 PR 커밋에서** 전달합니다. 재전달할 때는 오래된 파일과 섞지 않습니다.

| 파일 | 사용 시점 |
| --- | --- |
| `raw-external-record-partition.sql` | 원본 전환, 1회 |
| `raw-external-record-partition-check.sql` | 전환 직후·다음 날 읽기 점검 |
| `rollback-raw-external-record-partition.sql` | _old가 남아 있을 때 복구 |

함께 전달한 묶음의 `sql/` 폴더에는 위 세 파일과 `SHA256SUMS`가 있습니다.
AWS 담당자는 압축을 푼 폴더에서 DB 서버로 업로드합니다. 아래 `<DB_HOST>`는 실제 SSH 대상 주소로 바꿉니다.

```bash
scp -r sql ubuntu@<DB_HOST>:~/yon-db/
```

**DB 서버에서** 파일을 확인합니다. 모두 `OK`여야 합니다. 다르면 적용을 시작하지 않습니다.

```bash
cd ~/yon-db/sql
sha256sum -c SHA256SUMS
```

## 4. 진행 중 수집 확인 — DB 서버

```bash
cd ~/yon-db
docker compose exec -T postgres-db psql -X -U postgres -d yon -v ON_ERROR_STOP=1 <<'SQL'
SELECT id, source_id, status, sync_type, started_at
FROM application.sync_history WHERE status IN ('PENDING', 'RUNNING');
SELECT pg_size_pretty(pg_total_relation_size('application.raw_external_record')) AS raw_size;
SQL
```

**PENDING/RUNNING은 0행이어야 합니다.** 있으면 완료를 기다립니다.
팀이 수동 수집·배포를 중단한 상태를 유지하고 다음 단계로 갑니다.

## 5. 이미지 정보 보관·서비스 중지 — 데이터 서버

`deploy.env.before-raw-partition`이 이미 있으면 이전 작업 기록인지 확인합니다. 덮어쓰지 말고 백업 파일명을 합의한 뒤 아래 복구 명령에도 같은 이름을 사용합니다.

```bash
set -euo pipefail
cd ~/yon
test ! -e deploy.env.before-raw-partition
cp deploy.env deploy.env.before-raw-partition
docker compose --env-file .env --env-file deploy.env stop data-service
docker compose --env-file .env --env-file deploy.env ps -a data-service
```

**data-service가 중지됐는지 확인합니다.** 이때부터 새 버전 배포 완료까지 중지 상태를 유지합니다.
중지 직전 새 수집이 접수된 정황이 있으면 4단계 조회를 다시 하고, 0행이 아니면 코드 담당과 상태를 확인합니다.

## 6. DB 백업 — DB 서버

백업 파일은 DB 서버에 보관합니다. 아래 마지막 줄에 출력되는 경로를 작업 기록에 남깁니다.

```bash
set -euo pipefail
cd ~/yon-db
umask 077
raw_backup_file="$HOME/yon-before-raw-partition-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T postgres-db pg_dump -U postgres -d yon -Fc > "$raw_backup_file"
test -s "$raw_backup_file"
docker compose exec -T postgres-db pg_restore -l < "$raw_backup_file" > /dev/null
printf 'Backup: %s\n' "$raw_backup_file"
```

백업 생성·목록 검사가 실패하면 전환하지 않습니다. 목록 검사는 백업 파일의 형식을 확인하는 절차이며 실제 복원 리허설을 대체하지 않습니다.

## 7. 원본 전환 SQL — DB 서버

`postgres`로 접속하되 **SET ROLE application_user** 후 SQL을 실행합니다. `SET ROLE`을 생략하지 않습니다.

```bash
set -euo pipefail
cd ~/yon-db
test -s sql/raw-external-record-partition.sql
{
  printf '%s\n' 'SET ROLE application_user;'
  cat sql/raw-external-record-partition.sql
} | docker compose exec -T postgres-db psql -X -U postgres -d yon -v ON_ERROR_STOP=1 \
  | tee raw-partition-apply.log
```

**오류 없이 COMMIT이 출력돼야 합니다.** 잠금 획득 제한은 5초입니다.
ERROR가 나거나 연결이 끊기면 병합·배포를 진행하지 말고 11단계 판단표로 갑니다.
상태가 불명확하면 재실행하거나 옛 앱을 올리기 전에 DB 상태부터 확인합니다.

## 8. DB 점검·병합 요청 — DB 서버 / AWS 담당

```bash
set -euo pipefail
cd ~/yon-db
test -s sql/raw-external-record-partition-check.sql
{
  printf '%s\n' 'SET ROLE application_user;'
  cat sql/raw-external-record-partition-check.sql
} | docker compose exec -T postgres-db psql -X -U postgres -d yon -v ON_ERROR_STOP=1 \
  | tee raw-partition-check.log
```

| 확인 항목 | 정상 기준 |
| --- | --- |
| 부모 테이블 | relkind=p, LIST(source_id) |
| 파티션 | 등록 출처별 파티션과 DEFAULT 존재, DEFAULT 0행 |
| 복사 건수 | 중지 상태의 raw_external_record와 _old 건수가 같음 |
| 정제 요청 | source_id NULL 0건 |
| FK | 현재 원본·이전 원본 모두 (원본 ID, source_id) 복합 FK |
| ID | 중복 ID 0건, next_id가 max_id보다 큼 |
| 소유자 | 부모·자식·_old가 application_user |
| NOTIFY | raw_normalization_request_notify가 활성(tgenabled=O) |

점검 SQL은 결과를 출력하며 기대값을 자동 판정하지 않습니다.
**모두 정상이면 AWS 담당자가 코드 담당자에게 아래 내용을 전달합니다.**

> 원본 전환 COMMIT과 점검을 확인했습니다. DEFAULT·source_id NULL·중복 ID 모두 0건이고 건수·FK·시퀀스·소유자·트리거가 정상입니다. data-service는 중지 상태입니다. #86 병합을 진행해 주세요.

이 통보 전에는 #86을 병합하지 않습니다. 점검이 어긋나면 중지 상태로 복구 여부를 판단합니다.

## 9. PR 병합·자동 배포 — 코드 담당 / AWS 담당

1. 코드 담당자가 AWS 담당자의 정상 통보를 받은 뒤 #86을 병합합니다.
2. GitHub Actions의 해당 병합 커밋 `Data service` 테스트·이미지 게시가 성공하는지 확인합니다.
3. 이어지는 `Deploy`에서 **data-service / 데이터 서버 / 해당 커밋 이미지** 배포 성공을 확인합니다.
4. 작업 중 다른 main 병합·배포는 계속 보류합니다.

DB 전환 후에는 옛 이미지를 수동으로 먼저 기동하지 않습니다. 자동 배포가 새 컨테이너를 올립니다.
이미지 빌드·배포가 끝날 때까지 중지 시간이 이어집니다. 실패 시 11단계를 따릅니다.

## 10. 동작 확인 — 데이터 서버 + 관리자 API

데이터 서버에서 확인합니다.

```bash
cd ~/yon
docker compose --env-file .env --env-file deploy.env ps data-service
docker compose --env-file .env --env-file deploy.env logs --since 15m data-service
```

- Started DataServiceApplication이 보이고 DDL·FK·source_id 오류가 없어야 합니다.
- 관리자 API에서 기존 원본 ID 조회가 성공해야 합니다.
- 관리자 수동 수집으로 신규·변경·동일 원본 저장과 최종 수집 결과를 확인합니다.
- 새 정제 요청에 source_id가 채워지고 정상 처리되는지 DB 상태를 확인합니다.
- backend 날짜 추적 기능을 켜서 운영 중이면 준비 상태 조회 오류가 없는지 확인합니다.

gRPC 기동 점검 성공만으로 실제 수집·정제 성공을 판단하지 않습니다.
정상 확인 뒤 팀에 작업 종료를 알리고 보류했던 작업을 재개합니다. _old는 아직 삭제하지 않습니다.

## 11. 실패 시 판단과 복구

| 상황 | 처리 |
| --- | --- |
| 전환 전 준비·백업 실패 | 전환하지 않음. DB 미변경을 확인하고 기존 서비스 재개 여부 결정 |
| SQL 오류 / COMMIT 여부 불명확 | data-service 중지 유지. 부모 relkind와 _old 유무를 확인해 DB 상태부터 확정 |
| DB가 전환 전 일반 테이블 상태 | SQL 롤백 불필요. 기존 이미지 정보로 서비스를 복구하고 원인 확인 |
| COMMIT 후 점검·새 버전 배포·수집/정제 실패 | 서비스 중지 → 아래 원본 롤백 SQL → 이전 이미지 복구 |

**이미지 자동 롤백은 DB를 되돌리지 않습니다.** 배포 실패로 옛 컨테이너가 올라왔어도 정지한 뒤 SQL·이미지를 함께 복구합니다.

먼저 **데이터 서버**에서 중지합니다.

```bash
cd ~/yon
docker compose --env-file .env --env-file deploy.env stop data-service
```

그다음 **DB 서버**에서 _old가 존재하는 것을 확인하고 원본 롤백 SQL을 실행합니다.

```bash
set -euo pipefail
cd ~/yon-db
{
  printf '%s\n' 'SET ROLE application_user;'
  cat sql/rollback-raw-external-record-partition.sql
} | docker compose exec -T postgres-db psql -X -U postgres -d yon -v ON_ERROR_STOP=1
docker compose exec -T postgres-db psql -X -U postgres -d yon -v ON_ERROR_STOP=1 <<'SQL'
SELECT relkind FROM pg_class WHERE oid='application.raw_external_record'::regclass;
SQL
```

relkind=r이고 전환 후 들어온 원본·last_seen_at·정제 요청이 보존됐는지 확인합니다.
**정규화 ID 시퀀스 롤백 SQL을 함께 실행하지 않습니다.** 이번 복구는 원본 파티셔닝만 되돌립니다.

이후 **데이터 서버**에서 보관한 이미지 정보로 복구합니다.

```bash
cd ~/yon
cp deploy.env.before-raw-partition deploy.env
docker compose --env-file .env --env-file deploy.env up -d data-service
```

코드 담당자는 #86을 이미 병합했다면 revert PR로 다음 자동 배포도 이전 코드가 되도록 처리합니다.
정상 복구 확인 후에만 남은 파티션 복사본 `application.raw_external_record_p`를 별도로 삭제합니다.

## 12. 다음 날 확인·롤백 종료

다음 날 09:30 KST 정기 수집·정제 완료 후 DB 서버에서 점검 SQL을 다시 실행합니다.
서비스 재개 뒤에는 새 데이터가 생기므로 _old와 현재 테이블 건수가 달라도 정상입니다.

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

코드 담당과 AWS 담당이 정상 수집·정제·라우팅을 확인하고 **롤백 종료에 합의한 뒤에만** 아래를 실행합니다.
이후 제공된 롤백 SQL은 사용할 수 없습니다. 전체 DB 백업은 별도 보관 정책을 따릅니다.

```sql
SET ROLE application_user;
DROP TABLE application.raw_external_record_old;
```

## 13. 작업 기록

| 항목 | 담당자 기입 |
| --- | --- |
| 작업 일시 / 담당자 | |
| 검토한 PR 커밋 / SQL SHA256 확인 | |
| 전환 전 이미지 백업 파일 | |
| DB 백업 파일 경로 / 목록 검사 결과 | |
| 전환 COMMIT / 점검 결과 | |
| #86 병합 커밋 / Data service·Deploy 결과 | |
| 수동 수집 / 정제 확인 결과 | |
| 다음 날 정기 수집 / _old 삭제 결정 | |
