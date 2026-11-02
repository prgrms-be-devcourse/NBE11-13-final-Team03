#!/bin/sh
# 빈 데이터 디렉터리면 primary에서 pg_basebackup으로 채운 뒤 hot standby로 기동한다.
set -eu
PGDATA=/var/lib/postgresql/data
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  mkdir -p "$PGDATA" && chown postgres:postgres "$PGDATA" && chmod 700 "$PGDATA"
  echo "replica: pg_basebackup from $PRIMARY_HOST"
  until su-exec postgres env PGPASSWORD="$PRIMARY_PASSWORD" pg_basebackup -h "$PRIMARY_HOST" -U "$PRIMARY_USER" \
      -D "$PGDATA" -R -X stream; do
    echo "replica: basebackup retry in 3s"; rm -rf "${PGDATA:?}"/*; sleep 3
  done
fi
# 운영 deploy/db-replica 와 같은 옵션
exec docker-entrypoint.sh postgres -c hot_standby_feedback=on -c max_standby_streaming_delay=30s \
  -c shared_preload_libraries=pg_stat_statements -c track_io_timing=on
