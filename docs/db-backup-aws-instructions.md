# AWS 담당자 작업 순서 — 복제본 EC2 로컬 백업

이번 #92는 **yon-db-replica 디스크에 논리 덤프를 보관**한다. 새 AWS 자원을 만들지 않는다.
실행 대상은 ~/yon-db-replica, 프로젝트 yon-db-replica, 서비스 postgres-replica다.
기존 DB/복제 설정을 변경하지 않으며 정상 복제본 재시작도 필요하지 않다.

## 1. 적용 전 확인

| 확인 | 기준 |
| --- | --- |
| 대상 | 실제 replica EC2, pg_is_in_recovery()=true |
| 복제 | receiver=streaming, WAL 메시지 수신 경과 60초 미만, 재생 중지 아님 |
| 용량 | 제공값 DB 1.2GB / 디스크 29GB 중 22GB 여유, 적용 때 다시 측정 |
| 서버 가동 | 기동·정지 시각, 09:30 수집의 실제 종료, 일요일 가동 여부 |
| 백업 시각 | **11:00 KST 임시**. 실제 가동·수집 종료 확인 후 확정 |
| 도구 | Docker Compose 플러그인, Python 3.9 이상, tzdata |

스크립트의 기본 DB명은 yon, 사용자 postgres다. 실제 값이 다르면 ini에서 바꾼다.
기존 replica Compose에는 POSTGRES_USER/POSTGRES_DB가 없으므로 ini의 명시적 값을 사용한다.
예전에 #92의 옛 타이머를 설치했다면 옛 서버에서 먼저 비활성화하고 monthly 타이머도 중지한다.
옛 WAL 아카이브를 실제로 켰다면 타이머 중지만으로 해제되지 않으므로 기존 복제를 보존하는 별도 해제 절차를 확인한다.

## 2. 코드 담당자에게 받을 파일

검증한 커밋의 **deploy/db-replica/backup/** 폴더와 docs/db-backup.md를 받는다.
복제본 서버의 ~/yon-db-replica/backup/에 배치한다. 기존 .env·Compose·데이터 볼륨은 덮어쓰지 않는다.
백업 파일은 DB 데이터 볼륨과 다른 호스트 디렉터리 /var/lib/yon-backup에 보관한다.

## 3. 설정과 수동 실행

```bash
set -euo pipefail
cd ~/yon-db-replica
cp -n backup/backup.ini.example backup/backup.ini
chmod 600 backup/backup.ini
nano backup/backup.ini
```

release_reference와 실제 경로·프로젝트·서비스·DB명을 맞춘다.
실행 사용자가 ubuntu가 아니거나 경로가 다르면 systemd service의 User·Group·경로도 맞춘다.

```bash
sudo install -m 644 backup/yon-db-backup@.service backup/yon-db-backup*.timer /etc/systemd/system/
sudo systemctl daemon-reload
systemd-analyze calendar '*-*-* 11:00:00 Asia/Seoul'
sudo systemctl start yon-db-backup@daily.service
sudo systemctl start yon-db-backup@weekly.service
sudo systemctl start yon-db-backup@monitor.service
sudo journalctl -u yon-db-backup@daily.service -u yon-db-backup@monitor.service -n 50 --no-pager
```

초기 weekly는 기준 세트를 만드는 수동 실행이다. 이후 예약은 일요일에만 weekly를 추가한다.
완료 세트 안의 database.dump·globals.sql·manifest.json과 WAL 메시지 수신 시각·재생 중지 여부를 확인한다.
수신이 끊겼거나 오래됐거나 재생이 중지됐어도 백업은 만들어지지만 monitor는 실패한다. 원인을 해결한 뒤 최신 세트를 다시 만든다.

## 4. 복원 시험과 정기 실행

1. docs/db-backup.md의 절차로 로컬 세트 SHA256을 검증한다.
2. **다른 빈 볼륨·네트워크 없는 임시 DB**에 복원한다. 운영 DB에 직접 복원하지 않는다.
3. 파티션·주요 데이터·소유자·권한과 소요 시간을 코드 담당자와 확인한다.
4. 서버 가동/수집 종료에 맞는 시각을 확정하고 타이머를 활성화한다.

```bash
sudo systemctl enable --now yon-db-backup.timer yon-db-backup-monitor.timer
systemctl list-timers 'yon-db-backup*'
```

Persistent=false라 꺼진 시간의 예약을 부팅 즉시 보충하지 않는다. 11:00을 놓쳤다면 수집 종료 후 수동 실행한다.
다음 실제 예약 daily와 일요일 weekly, 보관 개수 7/4개·남은 공간·monitor를 확인해 완료로 기록한다.

## 5. 운영 정책

- 디스크가 80% 이상이면 덤프를 건너뛰고 실패한다. 기존 세트도 지우지 않으므로 공간 원인을 먼저 해결한다.
- 복제 충돌 취소만 5초 뒤 1회 재시도한다. 두 번째 실패나 다른 오류는 로그·종료 코드로 확인한다.
- 완료 manifest와 필수 파일이 정상인 오래된 세트만 개수에 따라 삭제한다. 강제 종료로 남은 불완전 폴더는 담당자가 실행 중 여부·경로를 확인한 뒤 정리한다.
- 일요일은 daily를 한 번 덤프한 후 weekly 파일 복사본을 추가한다. 복사 실패 시 daily는 유지되지만 작업은 실패한다.
- 복제 상태는 streaming·최근 WAL 메시지 수신 60초 미만·재생 중지 아님으로 판정한다. `max_wal_message_age_seconds=60`으로 설정하며 옛 `max_replay_age_seconds`는 사용하지 않는다. 마지막 트랜잭션 재생 시각은 진단용으로만 기록하므로 쓰기가 없는 시간에도 정상 판정한다. LSN 차이는 진단용이며 순간적인 차이는 실패시키지 않는다.
- 기존 기준에서 전환할 때는 [전환 절차](db-backup.md#기존-600초-재생-시각-기준에서-전환)에 따라 스크립트·설정을 교체하고 daily·weekly를 각각 새로 만든 뒤 monitor를 확인한다. 과거 manifest의 실패 기록은 수정하지 않는다.
- 외부 알림은 없다. 매 가동일 journal·monitor.json·남은 공간을 직접 확인한다. 서버가 꺼지면 monitor도 멈춘다.
- primary로 승격된 서버에서는 백업이 거부된다. 새 standby가 준비되면 설치 대상을 옮긴다.

**PITR 미지원, RPO 약 24시간.** 정상적인 일일 실행과 복제 최신성이 전제이며 실패·미실행·복제 지연으로 늘어날 수 있다.
primary와 replica가 동시에 유실되거나 백업을 보관한 디스크가 유실되면 복구할 외부 사본이 없다.

## 6. 완료 전달

| 항목 | 담당자 기입 |
| --- | --- |
| 작업 일시/담당자/파일 커밋 | |
| replica EC2/경로/프로젝트/서비스/DB명 | |
| 실제 기동·정지/일요일 가동/수집 종료/확정 백업 시각 | |
| daily·weekly 경로/완료 manifest/재생 시각/streaming | |
| 덤프 크기/보관 개수/디스크 여유 | |
| SHA256·별도 DB 복원·데이터·권한·소요 시간 | |
| 첫 예약 daily/일요일 weekly/monitor 결과 | |
| 다음 분기 복원 훈련/담당자 | |
