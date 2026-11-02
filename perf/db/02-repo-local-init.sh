#!/bin/sh
# 저장소의 로컬 DB 초기화 스크립트(database/local)를 CR 을 지운 사본으로 실행한다 (빈 볼륨에서 한 번).
#   02-set-local-passwords.sh : application_user·notification_user 비밀번호
#   03-apply-notification-ddl.sh : notification 스키마 테이블
set -eu
for f in 02-set-local-passwords.sh 03-apply-notification-ddl.sh; do
  echo "perf-init: $f"
  tr -d '\r' < "/yon-local/$f" > "/tmp/$f"
  bash "/tmp/$f"
done
