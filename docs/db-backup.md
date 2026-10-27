# PostgreSQL 정기 백업 운영

파티셔닝(#86·#87)은 완료됐다. 이 작업은 기존 primary의 정기 백업을 준비하며 파티셔닝 SQL을 다시 실행하지 않는다.
저장소 파일 병합은 운영 백업 활성화를 뜻하지 않는다. DB 서버는 자동 배포하지 않는다.
운영 인스턴스·Compose 경로·S3·IAM·Slack은 AWS 담당자 확인 대기 상태다.

## 담당과 완료 기준

| 담당 | 작업 |
| --- | --- |
| 코드 담당 | 파일·로컬 검증·PR 준비, 검증한 버전 전달, 운영 확인 결과 기록 |
| AWS 담당 | 실제 primary/볼륨 확인, 버킷·역할·네트워크·서버 파일 배치, 백업·복원 시험·타이머 실행 |
| 함께 | 첫 예약 백업과 알림 확인, 복원 데이터·권한·소요 시간 확인 |

운영 완료 기준은 **실제 S3 백업 + 별도 DB 복원 성공 + 다음 예약 백업/알림 성공**이다.
full/diff/WAL 운영 완료에는 첫 full·다음 diff·최신 복원·특정 시점 복원도 포함한다.
파일·테스트 완료와 운영 완료를 구분해 작업 기록에 남긴다.

## 배치와 일정

새 EC2가 필요하지 않다. PostgreSQL이 데이터 서버와 함께 있더라도 **실제 primary가 있는 인스턴스**에서 실행한다.
`~/yon-db`, Compose 프로젝트 `yon-db`, 서비스 `postgres-db`, 기존 `postgres_data` 볼륨이 기본값이다.
다르면 backup.ini와 systemd의 User/Group/경로를 실제 값으로 맞춘다. 다른 프로젝트명으로 DB를 새로 생성하지 않는다.
#91 읽기 복제본은 백업과 별개다. 스크립트는 standby에서 실행을 거부한다.

| 모드 | 일정(KST) | 저장 |
| --- | --- | --- |
| logical (초기) | 매일 03:30 전체 논리 덤프 | daily/<UTC시각-고유ID>/ |
| physical (최종) | 일 03:30 full, 월~토 03:30 diff | pgbackrest/ |
| physical | 상시 WAL 아카이브, 세그먼트 전환 300초 | pgbackrest/ |
| physical | 매월 첫 일요일 04:30 논리 덤프 | logical/<UTC시각-고유ID>/ |
| 수동 | DB 스키마 변경 직전 | manual/<UTC시각-고유ID>/ |
| 점검 | 기동 10분 뒤, 이후 5분마다 | 로컬 상태 + Slack |

systemd Persistent 타이머를 쓴다. 부팅 시 놓친 일정을 1회 보완한다.
정기 실행 시 full 성공 기록이 7일 이상 오래됐으면 요일과 무관하게 full을 선택한다.
호스트 flock으로 백업 중복을 막고, 겹친 작업은 SKIP 알림을 보낸다. 종료 후 담당자가 재실행한다.
03:30 full이 04:30까지 끝나지 않으면 월간 덤프가 SKIP될 수 있으므로 월간 작업을 다시 실행한다.

논리 덤프는 DB 전체(두 스키마·모든 파티션·시퀀스·소유자·권한)를 포함한다.
별도 globals.sql은 역할을 포함하되 비밀번호를 제외한다. 비밀번호·.env·앱 이미지 기록은 팀 비밀 저장소에 별도 보관한다.
파일별 SHA256·크기·DB/서버 버전·배포 참조가 담긴 manifest.json을 **마지막에** 올린다.
파일 업로드와 크기 검증이 모두 성공하고 manifest가 존재하는 세트만 복구 대상으로 선택한다.
복구 전 다운로드한 파일의 SHA256을 다시 검증한다. 목록 검사(pg_restore --list)는 실제 복원 시험을 대체하지 않는다.

## 1. AWS 준비

[AWS 담당자 지침](db-backup-aws-instructions.md)의 버킷·역할·네트워크 확인을 먼저 마친다.
실제 버킷 이름을 backup.ini와 pgbackrest.conf 양쪽에 동일하게 넣는다.
0단계 논리 덤프는 호스트 AWS CLI가 역할을 사용하므로 IMDS 홉 제한 변경이 필요하지 않다.
1단계 pgBackRest는 컨테이너가 역할을 사용하므로 해당 인스턴스의 IMDSv2/hop limit 2를 확인한다.

## 2. 초기 일일 백업 설치 — AWS 담당자

Ubuntu, Docker Compose 플러그인, Python 3.9 이상(권장 Ubuntu 24.04의 Python 3.12), tzdata, AWS CLI v2가 필요하다.
실행 사용자 ubuntu가 Docker를 사용할 수 있어야 한다. .env를 셸로 source하지 않는다.
`deploy/db/backup/`을 현재 DB Compose 폴더의 backup/에 전달한다. 기존 .env와 Compose는 보존한다.

```bash
set -euo pipefail
cd ~/yon-db
cp -n backup/backup.ini.example backup/backup.ini
chmod 600 backup/backup.ini
nano backup/backup.ini
```

mode=logical, 실제 bucket·compose_dir·service·state_dir·webhook_file·release_reference를 채운다.
release_reference에는 현재 배포 커밋이나 팀의 릴리스 번호를 적는다. 스키마가 변경될 때 갱신하고 해당 앱 이미지도 작업 기록에 남긴다.
Slack webhook은 별도 파일에 넣는다. 저장소·메신저 작업 결과에 실제 값을 붙이지 않는다.

```bash
cd ~/yon-db
umask 077
touch backup/slack-webhook
nano backup/slack-webhook
chmod 600 backup/slack-webhook
sudo install -m 644 backup/yon-db-backup@.service backup/yon-db-backup*.timer /etc/systemd/system/
sudo systemctl daemon-reload
systemd-analyze calendar '*-*-* 03:30:00 Asia/Seoul' 'Sun *-*-01..07 04:30:00 Asia/Seoul'
sudo systemctl start yon-db-backup@daily.service
sudo journalctl -u yon-db-backup@daily.service -n 50 --no-pager
```

systemd가 /var/lib/yon-backup을 ubuntu 소유·0700으로 만든다. 로그는 journal에 남겨 /var/log 파일 권한 문제를 피한다.
OK 알림·S3 세트·manifest를 확인하고 아래 논리 복원 시험을 완료한다. 실패하면 타이머를 켜지 않는다.

```bash
sudo systemctl enable --now yon-db-backup.timer yon-db-backup-monitor.timer
systemctl list-timers 'yon-db-backup*'
```

다음 예약 실행 후 OK와 S3의 새 manifest를 확인하면 초기 정기 백업 운영 완료다.
logical 모드의 복구 지점은 일 단위이며, 목표 RPO는 정상적인 매일 백업을 전제로 약 24시간이다.

## 3. 논리 복원 시험 — AWS 담당자 + 코드 담당

운영 DB로 직접 pg_restore하지 않는다. 작업 시간·메모리·디스크를 확인하고 별도 볼륨·네트워크 없는 컨테이너에서 시험한다.
t3.small에 DB·앱이 함께 있고 여유가 부족하면 비긴급 수집을 쉬는 시간에 진행한다. 복원 시간이 측정되기 전에는 RTO를 보장하지 않는다.
S3의 한 세트를 로컬 디렉터리에 다운로드한다. 아래 BACKUP_SET은 manifest가 있는 실제 경로로 바꾼다.

```bash
set -euo pipefail
umask 077
BACKUP_SET='s3://실제버킷/daily/실제세트'
RESTORE_DIR=$(mktemp -d "$HOME/yon-restore-files.XXXXXX")
aws s3 cp "$BACKUP_SET/manifest.json" "$RESTORE_DIR/manifest.json"
aws s3 cp "$BACKUP_SET/database.dump" "$RESTORE_DIR/database.dump"
aws s3 cp "$BACKUP_SET/globals.sql" "$RESTORE_DIR/globals.sql"
python3 - "$RESTORE_DIR" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / 'manifest.json').read_text())
for name in ('database.dump', 'globals.sql'):
    path = root / name
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    assert path.stat().st_size == manifest['files'][name]['bytes'], name
    assert digest.hexdigest() == manifest['files'][name]['sha256'], name
print('SHA256 OK:', manifest['database'], manifest['release_reference'])
PY
RESTORE_NAME="yon-restore-$(date +%Y%m%d%H%M%S)"
docker run -d --name "$RESTORE_NAME" --network none --cpus=1 --memory=768m \
  -e POSTGRES_USER=restore_admin -e POSTGRES_DB=yon -e POSTGRES_PASSWORD=restore-test-only \
  -v "$RESTORE_NAME:/var/lib/postgresql/data" postgres:16-alpine
```

manifest의 DB명이 yon이 아니면 POSTGRES_DB와 이후 -d 값을 맞춘다.
아래 ready 확인이 성공할 때까지 기다린 뒤 같은 셸에서 계속한다.

```bash
docker exec "$RESTORE_NAME" pg_isready -U restore_admin -d yon
docker exec -i "$RESTORE_NAME" psql -X -v ON_ERROR_STOP=1 -U restore_admin -d yon < "$RESTORE_DIR/globals.sql"
docker exec -i "$RESTORE_NAME" pg_restore --exit-on-error -U restore_admin -d yon < "$RESTORE_DIR/database.dump"
docker exec "$RESTORE_NAME" psql -X -U restore_admin -d yon -c '\dt application.*'
docker exec "$RESTORE_NAME" psql -X -U restore_admin -d yon -c '\dt notification.*'
```

restore_admin은 임시 슈퍼유저다. globals.sql의 postgres 역할과 충돌하지 않도록 다른 이름으로 초기화한다.
application_user·notification_user 소유권/권한, raw·행동 로그의 모든 자식 파티션, 주요 데이터와 시퀀스 증가를 확인한다.
온라인 DB와 단순 건수 비교는 그 사이 쓰기로 달라질 수 있다. 백업 시점 기준 데이터·알려진 표본과 함께 판단한다.
역할 비밀번호는 덤프에 없으므로 실제 복구 때는 비밀 저장소 값으로 대화형 재설정해야 한다.
테스트 컨테이너를 앱에 연결하지 않는다. 끝나면 이때 만든 RESTORE_NAME 컨테이너·볼륨만 확인 후 제거한다.
운영 볼륨을 지우거나 compose down -v를 실행하지 않는다.

## 4. full/diff/WAL 전환 — AWS 담당자

초기 일일 덤프와 복원 검증을 완료한 뒤 진행한다. 변경 직전 manual 백업을 만든다.
primary 복제 설정(#91의 pg_hba·슬롯·postgresql.auto.conf 등)과 기존 Compose 인자·볼륨을 확인하고 보존한다.
DB 이미지 재생성은 연결을 끊으므로 사용자·수집 트래픽이 적은 합의한 시간에 수행한다.

```bash
cd ~/yon-db
sudo systemctl start yon-db-backup@manual.service
cp -n backup/pgbackrest.conf.example backup/pgbackrest.conf
nano backup/pgbackrest.conf
```

bucket을 맞추고 IAM/IMDS/네트워크를 확인한다. 값 뒤에 주석을 덧붙이지 않는다.
docker-compose.backup.yml을 기존 Compose 옆에 둔다. 원본 Compose·기존 볼륨과 DB 설정을 보존한다.
아래 조합을 미리 검증하되 config의 실제 환경변수 출력을 작업 로그에 붙이지 않는다.

```bash
cd ~/yon-db
docker compose -f docker-compose.yml -f docker-compose.backup.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.backup.yml build postgres-db
docker compose -f docker-compose.yml -f docker-compose.backup.yml up -d postgres-db
docker compose -f docker-compose.yml -f docker-compose.backup.yml exec -T -u postgres postgres-db pgbackrest --stanza=yon stanza-create
docker compose -f docker-compose.yml -f docker-compose.backup.yml exec -T -u postgres postgres-db pgbackrest --stanza=yon check
```

backup.ini의 mode를 physical로 바꾸고 compose_files를 다음 두 줄로 설정한다.

```ini
compose_files=docker-compose.yml
    docker-compose.backup.yml
```

```bash
sudo systemctl start yon-db-backup@full.service
sudo systemctl start yon-db-backup@monthly.service
sudo systemctl start yon-db-backup@monitor.service
sudo systemctl enable --now yon-db-backup-monthly.timer
systemctl list-timers 'yon-db-backup*'
```

기존 일일 타이머는 scheduled를 실행하므로 재등록 없이 full/diff로 전환된다.
다음 diff 성공과 아래 물리 복원 시험을 확인한 뒤 최종 운영 완료로 기록한다.
도구 버전은 빌드 로그의 pgbackrest version, 서버 버전은 SHOW server_version으로 기록한다.
이후 수동 Compose 작업에서도 두 파일을 지정한다. 기본 Compose만으로 up하면 WAL 설정을 제거할 수 있다.

## 5. 물리 복원 훈련과 실제 장애

도입 직후와 분기마다 **다른 빈 볼륨**에서 최신 상태·특정 시점 복원을 시험한다.
복구 컨테이너는 앱에 연결하지 않고 포트를 공개하지 않으며, 시작 시 archive_mode=off로 저장소 쓰기를 막는다.
같은 메이저 PostgreSQL과 검증한 pgBackRest 이미지·설정을 사용한다. IMDS/S3 접근은 복원 컨테이너에서도 확인한다.

1. pgbackrest --stanza=yon --output=json info로 사용할 백업과 WAL 구간을 확인한다.
2. 같은 이미지로 별도 컨테이너·빈 볼륨을 만들고 backup/pgbackrest.conf를 읽기 전용으로 연결한다. 운영 데이터 볼륨은 공유하지 않는다.
3. PostgreSQL이 꺼진 상태에서 postgres 사용자로 pgbackrest --stanza=yon restore를 실행하면 최신 백업 + 보관 WAL을 복원한다.
4. 특정 시점은 그 **이전에 완료된** 백업을 --set으로 명시하고 --type=time --target='실제시각+09' --target-action=promote restore한다.
5. pg_ctl -D /var/lib/postgresql/data -l /tmp/recovery.log -o "-c archive_mode=off -c listen_addresses=''" -w start로 시험 DB를 시작한다.
6. 파티션 데이터·계정·권한·시퀀스와 목표 시각 전/후 표본을 확인하고 걸린 시간을 기록한다.

실제 장애에서는 쓰는 앱을 중지하고 기존 primary/볼륨을 보존한다. 검증한 새 복원 볼륨으로 전환한다.
복구 시점의 스키마에 맞는 앱 이미지 또는 검토된 후속 SQL이 준비돼야 앱을 다시 올릴 수 있다.
현재 .env의 이미지가 과거 스키마와 호환된다고 가정하지 않는다. 파티셔닝 이전 시점이면 #86·#87 적용 여부도 확인한다.
역할·호스트·DB_URL·보안 그룹을 확인하고, 실제 primary에서 archive_mode를 on으로 복구한 뒤 check와 새 full을 실행한다.
복제본은 새 primary의 타임라인에 맞춰 재구성하며 옛 primary를 동시에 쓰기 가능하게 올리지 않는다.
DB 복구가 이미 전송한 SQS/Slack/푸시까지 되돌리지는 않으므로 앱 재개 전에 알림/Outbox 재처리를 함께 확인한다.

테이블 부분 복구도 전체를 별도 DB로 복원한 뒤 비교·병합한다.
pg_dump -t 부모만으로 자식 파티션 데이터가 포함된다고 가정하지 않고, pg_restore가 테이블 이름을 바꿔 준다고 가정하지 않는다.

## 보관·장애 감지

| 대상 | 정책 |
| --- | --- |
| daily/ | 현재 버전 14일 + 이전 버전 삭제 유예 7일 |
| logical/, manual/ | 현재 버전 83일 + 이전 버전 7일, 약 90일 목표 |
| pgbackrest/ | 28일을 확보하는 time 보관. 그 경계 이전 full과 종속 diff/WAL이 추가로 필요할 수 있음 |
| S3 전체 | noncurrent 7일, 미완료 multipart 7일, 빈 delete marker 정리 |

S3 삭제는 비동기다. 정확한 시각의 영구 삭제나 모든 물리 백업의 90일 상한을 보장하지 않는다.
새 full이 계속 실패하면 마지막 복구 가능 세트를 먼저 지우지 않는다. full 오래됨 경고를 받고 원인을 해결한다.
pgbackrest/에 현재 객체 만료·Glacier 전환·Object Lock을 적용하지 않는다.

백업 성공이 30시간, full이 8일, 월간 논리 덤프가 40일을 넘으면 경고한다.
physical 모드에서 WAL .ready 파일의 가장 오래된 대기가 600초를 넘거나 DB 디스크 80% 이상이면 경고한다.
변경 없는 시간의 last_archived_time이 오래됐다는 이유만으로 장애를 판단하지 않는다.
archive_timeout=300은 세그먼트 전환 주기다. S3 전송 시간·실패를 포함한 5분 RPO 보장은 아니며 목표치로 관리한다.
동일 경고는 6시간마다 재알림하고 정상 복구를 알린다. webhook 실패는 작업 실패와 journal에 남긴다.
명령의 상세 오류는 비공개 상태 디렉터리의 last-command-error.log에 보관하며 Slack에는 파일 경로만 보낸다.
서버가 꺼지거나 타이머 자체가 멈추면 같은 서버의 점검도 멈춘다. AWS 담당자는 별도 서버 상태 감지와 매일 OK 수신 확인을 유지한다.
누락되면 systemctl status/list-timers와 journalctl을 확인한다. Slack에 성공이 없다는 사실도 운영 확인 대상이다.

## 작업 기록

| 항목 | 결과 |
| --- | --- |
| primary 인스턴스/경로/프로젝트/기존 볼륨 | AWS 담당자 확인 필요 |
| 버킷/역할/접속 확인 | AWS 담당자 확인 필요 |
| 파일 버전/현재 앱 이미지/스키마 참조 | |
| 초기 daily S3 세트/복원 결과/소요 시간 | |
| 첫 예약 백업/Slack | |
| pgBackRest·PostgreSQL 버전/재시작 일시 | |
| full/diff/WAL·최신/시점 복원 결과 | |
| 다음 분기 복원 훈련/담당자 | |

## 로컬 검증과 근거

실행 방법: python3 -m unittest discover -s deploy/tests -p test_db_backup.py -v 및
python3 deploy/tests/check_db_backup_postgres.py (Docker·Compose·AWS CLI 필요).
통합 검증은 고유 이름의 PostgreSQL 16·LocalStack S3만 사용하고 생성한 컨테이너·볼륨·네트워크를 정리한다.
논리 덤프/역할/파티션/권한, full/diff/WAL, 최신 상태·특정 시점 복원과 점검을 검증한다.
실제 AWS IAM·IMDS·운영 데이터·예약 실행은 이 테스트로 검증되지 않는다.

2026-10-27 로컬 결과: PostgreSQL 16.15, pgBackRest 2.58.0.
단위 검증 13개 통과. 논리 복원에서 raw 100건·행동 로그 100건·알림 1건과 소유자/권한 보존을 확인했다.
full+diff/WAL의 최신 복원은 raw 101건, 지정 시점 복원은 변경 전 100건이었다.
Ubuntu 24.04 + tzdata에서 일일/첫 일요일 KST 일정도 확인했다. 이 결과는 운영 적용 완료를 뜻하지 않는다.

- [pgBackRest 설정](https://pgbackrest.org/configuration.html): retention time, S3 auto credentials.
- [pgBackRest 복원](https://pgbackrest.org/user-guide.html): restore/PITR, target보다 앞선 백업 선택.
- [PostgreSQL pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html): globals는 별도, 테이블/자식 선택.
- [S3 버전 만료](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html).
