# PostgreSQL 복제본 로컬 정기 백업

#92는 **복제본 EC2의 로컬 논리 덤프**를 보관한다. S3·IAM·pgBackRest·WAL 아카이브는 구성하지 않는다.
파티셔닝(#86·#87)과 기존 물리 스트리밍 복제(#91)는 변경하지 않는다. 외부 알림 전송도 없다.

## 대상·보호 범위

| 항목 | 기준 |
| --- | --- |
| 실행 호스트 | yon-db-replica, `~/yon-db-replica` |
| Compose | 프로젝트 yon-db-replica / 서비스 postgres-replica |
| DB | PostgreSQL 16 standby, 기본 DB명 yon·사용자 postgres (운영 값 확인) |
| 보관 | /var/lib/yon-backup/daily, /var/lib/yon-backup/weekly |
| 현황 | 제공값: DB 1.2GB, 복제본 디스크 29GB 중 22GB 여유, 복제 지연 0.2ms |
| 복구 목표 | **PITR 미지원, RPO 약 24시간**. 복제 지연·미실행·실패 시 더 오래된 데이터만 복구 가능 |

primary 서버 유실은 기존 실시간 복제본과 이 서버의 덤프로 대응한다.
실수 삭제·잘못된 변경은 보관된 일별·주별 시점의 덤프로 복구한다.
**primary와 replica의 동시 유실에는 대응하지 않는다. 복제본 디스크를 잃으면 덤프 이력도 잃으며, AWS 외부 백업 사본은 없다.**
덤프 파일은 저장소·프런트 배포 폴더에 넣지 않는다. DB 데이터 볼륨과도 다른 호스트 디렉터리에 둔다.

백업 명령은 replica에만 접속한다. pg_is_in_recovery()가 true일 때만 시작하고 종료 직전에도 재확인한다.
primary이거나 덤프 중 승격되면 완료 세트를 발행하지 않는다. 승격 뒤 백업도 계속 거부한다.
primary에 백업용 쿼리는 보내지 않지만, 기존 hot_standby_feedback=on의 vacuum 지연 같은 간접 영향까지 0이라고 보장하지는 않는다.

## 실행과 보관

| 작업 | 기준 |
| --- | --- |
| 매일 | 11:00 KST daily 전체 논리 덤프 |
| 일요일 | 같은 덤프를 weekly에 검증·복사. DB 덤프를 두 번 실행하지 않음 |
| daily 보관 | manifest가 유효하고 필수 파일·크기가 일치하는 최근 완료 세트 7개 |
| weekly 보관 | 같은 기준의 최근 완료 세트 4개 |
| 점검 | 부팅 10분 뒤부터 5분마다, journal·monitor.json·종료 코드로 확인 |

11:00은 09:30 수집 이후의 **임시 실행 시각**이다. 실제 수집 종료·서버 기동/정지·일요일 가동 시간을 AWS 담당자가 확인해야 한다.
Persistent=false로, 서버 부팅 때 밀린 백업을 수집 종료 전 즉시 실행하지 않는다.
11:00에 꺼져 있으면 그날 예약을 놓친다. 운영자가 수집 종료 뒤 수동 실행하거나 일정을 조정한다.
일요일을 놓친 weekly는 다음 가동 시간에 수동 실행한다. 야간 정지로 생긴 복제 지연도 기동 후 확인한다.

daily/weekly는 모두 독립적으로 복원 가능한 **전체 논리 덤프**다. 차등 백업이 아니다.
보관은 성공 세트 개수 기준이다. 매일/매주 성공하면 약 7일/4주 구간이 생기지만, 수동 추가 성공·누락에 따라 실제 보관 기간이 달라진다.
weekly는 파일 복사본이므로 daily가 만료돼도 유지된다. 디스크를 별도로 사용한다.
DB 1.2GB를 세트 크기로 가정하면 11세트는 약 13.2GB지만 실제 압축률·DB 증가·임시 덤프·복원 공간을 측정해야 한다.
보관 한도 도달 후에도 새 덤프와 일요일 복사본을 만들 여유가 먼저 필요하다.

## 백업 흐름

1. 호스트 flock으로 중복 실행을 막는다. 중복이면 SKIP 로그와 종료 코드 1, 기존 작업은 유지한다.
2. standby 여부, WAL receiver 상태·메시지 수신 시각, 재생 중지 여부·LSN을 조회한다.
3. **백업 경로 또는 DB 데이터 디스크가 80% 이상이면 덤프 전 실패 처리**한다. 기존 세트도 삭제하지 않는다.
4. 같은 파일시스템의 .incomplete-* 폴더에 pg_dump -Fc와 globals.sql을 만든다.
5. 복제 충돌(`conflict with recovery`)로 취소된 경우 5초 뒤 전체 덤프를 **1회만 재시도**한다. 다른 실패는 재시도하지 않는다.
6. pg_restore --list, 파일 크기·SHA256 재검증, 디스크 한도·standby 재확인을 한다.
7. 파일을 동기화하고 **manifest.json을 마지막에 기록**, 폴더 이름을 원자적으로 바꿔 완료 세트를 공개한다.
8. 성공 상태를 기록한 뒤 오래된 완료 세트만 정리한다. 불완전·손상 manifest·관련 없는 폴더·심볼릭 링크는 삭제 대상으로 삼지 않는다.
9. 일요일은 daily를 검증해 weekly에 복사·완료 표시하고 weekly 보관 한도를 적용한다.

데이터 파일은 database.dump와 globals.sql이다. DB 전체 덤프에 모든 파티션·시퀀스·소유자·권한을 포함한다.
globals.sql은 역할 정보를 포함하되 비밀번호를 제외한다. 실제 비밀번호·.env·pg_hba·복제 접속 설정·현재 앱 이미지 정보는 별도 보관한다.
실패한 새 덤프는 완료로 표시하지 않으며 기존 성공 세트를 대신하지 않는다. 디스크 한도는 덤프 후·weekly 복사 전후에도 재확인한다.
일요일에 weekly 복사만 실패하면 성공한 daily는 보존하고 전체 작업은 실패로 기록한다. 공간 문제를 해결한 뒤 weekly를 수동 실행한다.
서버 강제 종료로 남은 .incomplete-* 폴더는 보관 개수에 포함하지 않는다. 실행 중이 아님을 확인한 담당자가 내용·경로를 검토해 정리한다.

## 복제 최신성과 monitor

manifest에는 덤프 전후의 다음 정보가 남는다.

- in_recovery, checked_at
- pg_last_xact_replay_timestamp()의 replay_timestamp와 그 경과 초(replay_age_seconds)
- pg_stat_wal_receiver의 wal_receiver_status, last_msg_receipt_time과 경과 초(last_msg_age_seconds)
- receive_lsn, replay_lsn, replay_lag_bytes, replay_paused
- freshness_problems, dump_attempts, 파일 크기·SHA256, DB·서버 버전·배포 참조

**streaming이 끊겼거나 WAL 메시지 수신이 오래됐거나 재생이 중지돼도 덤프는 보관한다.** 다만 manifest에 문제를 남기고 monitor는 종료 코드 1로 실패한다.
정상 기준은 streaming, 메시지 수신 경과가 `max_wal_message_age_seconds`(기본 60초) 미만, 재생 중지 아님이다. 수신 시각이 없거나 경과가 음수여도 실패한다.
마지막 트랜잭션 재생 시각은 진단용으로만 기록한다. 쓰기가 없는 시간에는 오래되거나 없을 수 있으므로 실패 조건으로 쓰지 않는다.
LSN 차이는 수신 완료 WAL 대비 미재생 바이트이며 0이면 해당 WAL은 재생 완료다. 순간적인 비동기 재생 차이로 오탐하지 않도록 0보다 크다는 이유만으로 실패시키지 않는다. 이 점검은 primary의 현재 WAL과의 완전한 일치나 지연 시간 상한을 보장하지 않는다.
각 시각은 덤프 전후의 관측값이며 pg_dump 스냅샷과 정확히 같은 시점임을 보장하지 않는다.

monitor는 현재 복제 상태 외에도 최근 daily/weekly manifest의 freshness_problems를 확인한다.
연결이 돌아와도 마지막 보관 세트가 오래된 상태에서 만들어졌다면 실패를 유지한다. 최신 상태의 daily/weekly를 새로 만들어야 해소된다.
daily 30시간·weekly 8일 초과, 세트 파일 누락/크기 이상, 디스크 80% 이상도 실패다.
monitor는 매 5분마다 큰 덤프 전체 해시를 다시 읽지 않는다. SHA256 전체 검증은 생성·weekly 복사·복원 전에 한다.

### 기존 600초 재생 시각 기준에서 전환

`backup.py`를 교체하고 기존 `backup.ini`의 `max_replay_age_seconds=600`을 `max_wal_message_age_seconds=60`으로 바꾼다. 새 항목이 없어도 기본 60초를 적용하며, 옛 항목은 사용하지 않는다.
기존 manifest의 `freshness_problems`는 당시 기록이므로 수정하거나 지우지 않는다. 기존 daily 또는 weekly에 오탐이 기록됐다면 복제 정상 상태에서 아래처럼 **두 종류 모두 새 기준 세트**를 생성한 뒤 monitor를 확인한다. weekly 수동 실행은 전환 때 한 번 수행한다.

```bash
sudo systemctl start yon-db-backup@daily.service
sudo systemctl start yon-db-backup@weekly.service
sudo systemctl start yon-db-backup@monitor.service
sudo journalctl -u yon-db-backup@monitor.service -n 30 --no-pager
```

스크립트·설정만 교체하면 systemd daemon-reload는 필요 없다. 현재 연결이 복구돼도 과거 덤프 시점의 문제를 정상으로 덮어쓰지 않는다.

## 설치 — 복제본 EC2에서 AWS 담당자

Ubuntu, Docker Compose 플러그인, Python 3.9 이상과 tzdata가 필요하다. AWS CLI나 새 AWS 자원은 필요하지 않다.
현재 Compose·볼륨·복제 설정을 보존하고 **deploy/db-replica/backup/** 폴더만 ~/yon-db-replica/backup/에 배치한다.
기존 replica 이미지/Compose는 수정하지 않으므로 정상 복제본의 재시작도 필요하지 않다.

```bash
set -euo pipefail
cd ~/yon-db-replica
cp -n backup/backup.ini.example backup/backup.ini
chmod 600 backup/backup.ini
nano backup/backup.ini
```

현재 배포 참조, 실제 DB명·사용자, Compose 프로젝트/경로·서비스와 state_dir을 확인한다.
replica Compose에는 POSTGRES_USER/POSTGRES_DB가 없으므로 ini의 db_user/db_name을 사용한다.
실행 사용자 ubuntu가 Docker를 사용할 수 있어야 한다. 다른 사용자/경로라면 service 파일도 맞춘다.

```bash
cd ~/yon-db-replica
sudo install -m 644 backup/yon-db-backup@.service backup/yon-db-backup*.timer /etc/systemd/system/
sudo systemctl daemon-reload
systemd-analyze calendar '*-*-* 11:00:00 Asia/Seoul'
sudo systemctl start yon-db-backup@daily.service
sudo systemctl start yon-db-backup@weekly.service
sudo systemctl start yon-db-backup@monitor.service
sudo journalctl -u yon-db-backup@daily.service -u yon-db-backup@monitor.service -n 50 --no-pager
```

초기 weekly는 첫 일요일 전에도 수동으로 기준 세트를 만든다. 이후 예약은 일요일에만 추가한다.
systemd가 /var/lib/yon-backup을 ubuntu 소유·0700으로 만든다. backup.ini의 state_dir을 바꾸면 소유권·공간도 별도로 확인한다.
완료 세트·SHA256·복원 시험과 복제 최신성을 확인한 뒤, 실제 서버 가동 시간을 확인해 타이머를 켠다.

```bash
sudo systemctl enable --now yon-db-backup.timer yon-db-backup-monitor.timer
systemctl list-timers 'yon-db-backup*'
```

이전 #92 버전을 이미 설치했다면 **옛 서버의 백업 타이머를 먼저 비활성화**한다. 옛 monthly 타이머도 중지한다.
이전 안의 WAL 아카이브까지 운영 적용했다면 타이머만 꺼도 WAL 설정은 남는다. 담당자가 기존 스트리밍 복제를 보존하면서 해제 절차를 별도로 확인한다.
현재 코드가 옛 primary 설정을 자동으로 바꾸거나 저장소를 삭제하지 않는다.

## 로컬 세트 복원 시험

도입 직후와 분기마다 다른 빈 볼륨·네트워크 없는 임시 컨테이너에서 시험한다. 운영 DB에 바로 pg_restore하지 않는다.
충분한 메모리·디스크를 확인하고 다음 BACKUP_SET을 유효한 세트 경로로 바꾼다.

```bash
set -euo pipefail
BACKUP_SET='/var/lib/yon-backup/daily/실제세트'
python3 - "$BACKUP_SET" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / 'manifest.json').read_text())
for name in ('database.dump', 'globals.sql'):
    path, digest = root / name, hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    assert path.stat().st_size == manifest['files'][name]['bytes'], name
    assert digest.hexdigest() == manifest['files'][name]['sha256'], name
print('SHA256 OK', manifest['replication_before'], manifest['replication_after'])
PY
RESTORE_NAME="yon-restore-$(date +%Y%m%d%H%M%S)"
docker run -d --name "$RESTORE_NAME" --network none --cpus=1 --memory=768m \
  -e POSTGRES_USER=restore_admin -e POSTGRES_DB=yon -e POSTGRES_PASSWORD=restore-test-only \
  -v "$RESTORE_NAME:/var/lib/postgresql/data" postgres:16-alpine
```

실제 DB명이 yon이 아니면 POSTGRES_DB와 아래 -d 값을 맞춘다. ready 확인이 성공한 뒤 같은 셸에서 계속한다.

```bash
docker exec "$RESTORE_NAME" pg_isready -U restore_admin -d yon
docker exec -i "$RESTORE_NAME" psql -X -v ON_ERROR_STOP=1 -U restore_admin -d yon < "$BACKUP_SET/globals.sql"
docker exec -i "$RESTORE_NAME" pg_restore --exit-on-error -U restore_admin -d yon < "$BACKUP_SET/database.dump"
docker exec "$RESTORE_NAME" psql -X -U restore_admin -d yon -c '\dt application.*'
docker exec "$RESTORE_NAME" psql -X -U restore_admin -d yon -c '\dt notification.*'
```

restore_admin은 백업의 postgres 역할과 충돌하지 않는 임시 슈퍼유저다.
파티션 데이터·알려진 표본·소유자/권한·시퀀스를 확인한다. 비밀번호는 포함하지 않으므로 실제 앱 재개 전 비밀 저장소 값으로 재설정한다.
복구 세트의 스키마와 맞는 앱 이미지/후속 SQL도 확인한다. DB 복구가 이미 보낸 외부 알림을 취소하지는 않는다.
끝나면 이때 만든 RESTORE_NAME 컨테이너와 볼륨만 확인 후 제거한다. 기존 운영 DB 볼륨은 지우지 않는다.

primary 장애 시 실시간 replica 승격과 DB 접속 주소 전환은 기존 #91 절차를 따른다.
승격된 서버에서는 이 백업 스크립트가 거부되므로 새 standby를 구축한 뒤 설치 대상을 옮긴다.
잘못된 변경 복구는 먼저 별도 DB에 덤프를 복원하고, 전체 되돌림 또는 필요한 데이터 병합을 검토한다.

## 운영 확인과 인계

```bash
systemctl show yon-db-backup@scheduled.service -p Result -p ExecMainStatus
sudo journalctl -u yon-db-backup@scheduled.service -u yon-db-backup@monitor.service --since today --no-pager
sudo cat /var/lib/yon-backup/monitor.json
```

백업 실행 성공은 세트 생성 성공이며, 최신성 정상과는 별개다. 실제 예약 실행·세트의 재생 시각·monitor를 함께 확인한다.
같은 서버가 꺼지면 monitor도 멈춘다. 운영자가 매 가동일 직접 로그·상태·남은 공간을 확인한다.

| 항목 | 담당자 기록 |
| --- | --- |
| 복제본 EC2/경로/프로젝트/서비스/DB명 | |
| 실제 기동·정지/수집 종료/일요일 가동 시간·확정 백업 시각 | 11:00 KST 임시, 확인 필요 |
| 코드 커밋/배포 참조/사용 중 앱 이미지 | |
| 실제 덤프 크기/전체 세트 용량/남은 디스크 | |
| 첫 daily·weekly/재생 시각/streaming/monitor | |
| 로컬 SHA256·별도 DB 복원·소유자·권한·소요 시간 | |
| 다음 예약 실행/다음 분기 훈련 | |

## 검증

단위 테스트는 primary 거부·1회 재시도·7/4개 보관·불완전 세트 제외·디스크 한도·복제 최신성 등을 확인한다.
통합 테스트는 PostgreSQL 16의 실제 primary와 스트리밍 standby를 만들고 standby 덤프를 별도 DB에 복원한다.
연결 중단 중 덤프 보관/monitor 실패, 재연결 후 복구, 승격 후 실행 거부도 확인한다.
운영 EC2·실제 1.2GB 데이터·확정 예약 시각의 실행을 검증한 것은 아니다.

2026-10-27 로컬 검증: 단위 22개 통과, PostgreSQL 16.15 실제 스트리밍 복제본의 로컬 덤프·복원 통과.
raw 100건·행동 로그 100건·notification 스키마 1건과 파티션·소유자·권한을 확인했다.
primary 중단 중 덤프 보관/점검 실패, 재연결 후 정상 점검, primary 및 승격 후 실행 거부를 확인했다.
11:00 KST 일정 문법은 CI의 systemd-analyze 단계에서 확인하며 실제 가동 시간은 운영 담당자가 확정한다.

근거: [PostgreSQL hot standby](https://www.postgresql.org/docs/16/hot-standby.html),
[재생 시각 함수](https://www.postgresql.org/docs/16/functions-admin.html),
[hot_standby_feedback](https://www.postgresql.org/docs/16/runtime-config-replication.html#GUC-HOT-STANDBY-FEEDBACK).
