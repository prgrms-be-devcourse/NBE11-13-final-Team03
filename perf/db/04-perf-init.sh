#!/bin/sh
# 측정용 DB 초기화 (빈 볼륨에서 한 번). 운영과 다른 점은 측정 확장과 복제 접속 허용뿐이다.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SQL
# 선택 복제본(compose.replica.yaml)의 pg_basebackup 접속 허용 (측정 전용 네트워크 안에서만)
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
