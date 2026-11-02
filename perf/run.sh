#!/usr/bin/env bash
# Y:ON 성능·부하 측정 실행기. 저장소 루트나 perf/ 어디서 실행해도 된다 (Git Bash, WSL, Linux, macOS).
#
#   ./perf/run.sh init                      perf/perf.env 생성(측정 전용 JWT 키·코어 배치) + 호스트 점검
#   ./perf/run.sh build <tag>               지금 체크아웃된 코드로 서버 이미지 3개를 <tag>로 빌드
#   ./perf/run.sh seed                      PERF_TAG 이미지로 측정용 DB 스냅샷 생성 (수집·정규화·사용자 시드)
#   ./perf/run.sh smoke                     짧게 한 번씩 돌려 스크립트·환경 확인 (결과는 results/smoke)
#   ./perf/run.sh bench [옵션]              반복 측정
#        --label NAME        결과 묶음 이름 (기본: 날짜)
#        --versions a,b      비교할 이미지 태그. 두 개 이상이면 회차마다 순서를 바꿔 번갈아 실행 (ABAB/BABA)
#        --scenarios LIST    perf,loadA,loadB,probe 중 (기본 perf,loadA,loadB)
#        --runs N            시나리오·버전별 회차 (기본 perf.env RUNS, 7)
#        --probe-runs N      probe 회차 (기본 3)
#        --profile credit    t3 크레딧 소진 상태 모사(서버 컨테이너 cpus 0.4)로 1회성 참고 측정
#   ./perf/run.sh report <label>            집계표·전후 비교 다시 만들기
#   ./perf/run.sh down                      측정 스택 내리기 (스냅샷 볼륨은 유지)
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash 경로 자동 변환 끄기

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PERF_DIR/.." && pwd)"
cd "$PERF_DIR"

# perf.env 값은 기본값으로만 쓴다 (이미 환경변수로 준 값이 우선: 예 PERF_TAG=opt1 bash perf/run.sh smoke)
load_env_defaults() {
  local k v
  while IFS='=' read -r k v; do
    [[ $k =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    [ -z "${!k+x}" ] && export "$k=$v"
  done < <(tr -d '\r' < "$1")
  return 0
}
[ -f perf.env ] && load_env_defaults perf.env
# 측정 스택 전용 비밀값(DB 비밀번호·내부 키·JWT 키). init 이 임의 값으로 만든다. 로컬 개발용 docker/.env 와 무관하다.
ENV_FILE="$PERF_DIR/secrets.env"
DB_USERNAME=postgres
DB_NAME=yon

PG_VOLUME=${PERF_PG_VOLUME:-yon-perf-pgdata}
SNAP_VOLUME=${PERF_SNAP_VOLUME:-yon-perf-pgsnap}
RUNS=${RUNS:-7}
export PERF_PG_VOLUME=$PG_VOLUME

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

compose_files() {
  local files=(-f compose.perf.yaml)
  [ "${PERF_REPLICA:-0}" = "1" ] && files+=(-f compose.replica.yaml)
  [ "${PERF_PROFILE:-}" = "credit" ] && files+=(-f compose.credit.yaml)
  echo "${files[@]}"
}
dc() { docker compose --env-file "$ENV_FILE" --env-file perf.env $(compose_files) "$@"; }
psql_db() { dc exec -T db psql -U "$DB_USERNAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"; }
psql_app() { # application_user 권한으로 SQL 파일 실행 (운영 수동 SQL과 같은 방식)
  { echo 'SET ROLE application_user;'; echo 'SET search_path TO application, pg_catalog;'; cat "$1"; } | psql_db -q
}
tools() { dc --profile tools run --rm -T tools "$@"; }
server_services() {
  local s=(db ext-mock data-service notification-service backend)
  [ "${PERF_REPLICA:-0}" = "1" ] && s+=(db-replica)
  echo "${s[@]}"
}

# ------------------------------------------------------------------ init
cmd_init() {
  command -v docker >/dev/null || die "docker 명령이 없습니다"
  docker info >/dev/null 2>&1 || die "Docker 가 실행 중이 아닙니다 (Docker Desktop 을 켜세요)"
  docker compose version >/dev/null 2>&1 || die "docker compose(v2)가 필요합니다"
  if [ ! -f perf.env ]; then
    cp perf.env.example perf.env
    local key
    key=$(head -c 64 /dev/urandom | base64 | tr -d '\n')
    sed -i.bak "s|^PERF_JWT_SECRET_KEY=.*|PERF_JWT_SECRET_KEY=$key|" perf.env && rm -f perf.env.bak
    log "perf/perf.env 생성 (측정 전용 JWT 키 포함)"
  fi
  load_env_defaults perf.env
  if [ ! -f secrets.env ]; then
    write_secrets > secrets.env
    log "perf/secrets.env 생성 (측정 전용 임의 비밀값)"
  fi
  local ncpu mem_gib
  ncpu=$(docker info --format '{{.NCPU}}')
  mem_gib=$(( $(docker info --format '{{.MemTotal}}') / 1024 / 1024 / 1024 ))
  log "Docker 호스트: CPU ${ncpu}개, 메모리 ${mem_gib}GiB"
  local need=10; [ "${PERF_REPLICA:-0}" = "1" ] && need=12
  [ "$ncpu" -ge "$need" ] || die "Docker 에 보이는 CPU가 ${ncpu}개입니다. ${need}개 이상 필요합니다 (%UserProfile%\.wslconfig 의 [wsl2] processors 를 늘리고 'wsl --shutdown' 후 Docker Desktop 재시작)"
  [ "$mem_gib" -ge 11 ] || die "Docker 메모리가 ${mem_gib}GiB 입니다. 11GiB 이상 필요합니다 (.wslconfig 의 memory=12GB 이상)"
  local last=$((ncpu - 1)) v hi
  for v in "$CPUSET_MAIN" "$CPUSET_DATA" "$CPUSET_DB" "$CPUSET_TOOLS"; do
    hi=${v##*-}; [ "$hi" -le "$last" ] || die "perf.env 의 코어 배치($v)가 호스트 CPU 0-$last 를 넘습니다"
  done
  log "환경 점검 통과"
}

rand_hex() { od -An -tx1 -N24 /dev/urandom | tr -d ' \n'; }

write_secrets() {
  echo "# 측정 스택 전용 비밀값 (perf/run.sh init 생성, 커밋 금지). 운영·개발 값과 무관하다."
  echo "DB_USERNAME=postgres"
  echo "DB_PASSWORD=$(rand_hex)"
  echo "DB_NAME=yon"
  echo "APPLICATION_DB_PASSWORD=$(rand_hex)"
  echo "NOTIFICATION_DB_PASSWORD=$(rand_hex)"
  echo "JWT_SECRET_KEY=$PERF_JWT_SECRET_KEY"
  echo "KAKAO_CLIENT_ID=perf-not-used"
  echo "KAKAO_CLIENT_SECRET=perf-not-used"
  echo "ADMIN_KAKAO_IDS="
  echo "INTERNAL_API_KEY=$(rand_hex)"
  echo "DATA_SERVICE_INTERNAL_API_KEY=$(rand_hex)"
  echo "NOTIFICATION_INTERNAL_API_KEY=$(rand_hex)"
  echo "# 알림 화면 API(backend -> notification-service)는 켠다. 발행(Outbox)·수신·푸시는 운영 기본값처럼 끈다."
  echo "NOTIFICATION_FACADE_ENABLED=${PERF_NOTIFICATION_FACADE:-true}"
  echo "NOTIFICATION_USER_API_ENABLED=${PERF_NOTIFICATION_FACADE:-true}"
  echo "NOTIFICATION_PUBLISHER_ENABLED=false"
  echo "NOTIFICATION_RECEIVER_ENABLED=false"
  echo "COLLECTION_SCHEDULE_ENABLED=false"
  echo "YON_NORMALIZATION_INGEST_ENABLED=true"
  echo "YON_NORMALIZATION_CONDITION_SET_BACKFILL_ENABLED=true"
  echo "REGION_CODE_SYNC_ON_STARTUP=false"
}

# ------------------------------------------------------------------ build
cmd_build() {
  local tag=${1:?태그가 필요합니다. 예: ./perf/run.sh build base}
  local sha dirty=""
  sha=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)
  # 줄바꿈(CRLF) 차이만 있는 파일은 변경으로 보지 않는다. 이 검사는 표시용이며 실패해도 빌드를 멈추지 않는다.
  if ! git -C "$ROOT_DIR" diff --quiet --ignore-cr-at-eol HEAD -- backend data-service notification-service contracts >/dev/null 2>&1; then
    dirty="-dirty"
    log "커밋하지 않은 서버 코드 변경이 있습니다 (줄바꿈 차이 제외). 이미지 라벨에 -dirty 를 붙입니다"
  fi
  if [ "${PERF_BUILD_ON_HOST:-0}" = "1" ]; then
    for svc in backend data-service notification-service; do
      log "bootJar(호스트): $svc"; (cd "$ROOT_DIR/$svc" && ./gradlew bootJar -x test -q)
    done
  else
    # 호스트에 JDK 25 가 없어도 되도록 JDK 컨테이너 안에서 빌드한다. Gradle 캐시는 볼륨으로 재사용한다.
    # gradlew 스크립트 대신 wrapper jar 를 직접 실행한다 (Windows 체크아웃의 CRLF gradlew 는 리눅스에서 실행되지 않음).
    log "bootJar (eclipse-temurin:25-jdk 컨테이너. 처음엔 의존성 내려받기로 수 분 걸림)"
    docker run --rm -v "$ROOT_DIR":/src -v yon-perf-gradle:/root/.gradle -w /src eclipse-temurin:25-jdk \
      bash -c 'set -e; for s in backend data-service notification-service; do echo "  - $s"; (cd $s && java -Dorg.gradle.appname=gradlew -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain bootJar -x test -q --no-daemon); done'
  fi
  for svc in backend data-service notification-service; do
    ls "$ROOT_DIR/$svc"/build/libs/*.jar >/dev/null 2>&1 || die "$svc/build/libs 에 jar 가 없습니다 (빌드 실패)"
    log "docker build: yon-$svc:$tag ($sha$dirty)"
    docker build -q -t "yon-$svc:$tag" --label "yon.perf.git-sha=$sha$dirty" "$ROOT_DIR/$svc" >/dev/null
  done
  log "빌드 완료: tag=$tag commit=$sha$dirty"
}

# ------------------------------------------------------------------ seed
wait_sql() { # wait_sql "<SQL returning one number>" <expected-op> <value> <timeout-sec>
  local sql=$1 op=$2 want=$3 timeout=$4 t=0 v
  while :; do
    v=$(psql_db -At -c "$sql" 2>/dev/null | tr -d '[:space:]' || true)
    if [ -n "$v" ] && [ "$v" "$op" "$want" ]; then echo "$v"; return 0; fi
    [ "$t" -ge "$timeout" ] && { echo "${v:-none}"; return 1; }
    sleep 5; t=$((t + 5))
  done
}

wait_stable() { # 값이 3회 연속(15초) 같으면 안정으로 본다
  local sql=$1 timeout=${2:-1800} prev="" same=0 t=0 v
  while [ "$t" -lt "$timeout" ]; do
    v=$(psql_db -At -c "$sql" | tr -d '[:space:]')
    if [ "$v" = "$prev" ]; then same=$((same + 1)); else same=0; prev=$v; fi
    [ "$same" -ge 3 ] && { echo "$v"; return 0; }
    sleep 5; t=$((t + 5))
  done
  echo "$v"; return 1
}

cmd_seed() {
  : "${PERF_TAG:?perf.env 의 PERF_TAG(시드에 쓸 이미지 태그)가 필요합니다}"
  local users=${PERF_USERS:-1000}
  log "시드 시작: image=$PERF_TAG users=$users (기존 측정 DB·스냅샷을 새로 만듭니다)"
  dc down --remove-orphans >/dev/null 2>&1 || true
  docker volume rm -f "$PG_VOLUME" >/dev/null
  mkdir -p data results/_scratch/jfr && chmod 777 results/_scratch/jfr 2>/dev/null || true
  export RUN_DIR=./results/_scratch MOCK_DAY=0 PERF_REGION_SYNC=true

  log "1/8 DB·목·data-service 기동 (지역 코드 동기화 포함)"
  dc up -d --wait db ext-mock data-service
  wait_sql "select count(*) from information_schema.tables where table_schema='application' and table_name in ('opportunities','raw_normalization_request','application_windows','opportunity_conditions')" -ge 4 300 >/dev/null \
    || die "data-service 테이블이 만들어지지 않았습니다 (docker compose logs data-service 확인)"
  wait_sql "select count(*) from application.regions" -gt 0 300 >/dev/null || die "지역 코드 동기화가 되지 않았습니다"

  log "2/8 운영 수동 SQL 적용 (정규화 NOTIFY 트리거, 검색 제목 생성 컬럼, 검색 인덱스)"
  psql_app "$ROOT_DIR/data-service/src/main/resources/db/scripts/normalization-notifications.sql"
  psql_app "$ROOT_DIR/database/scripts/search-title-columns.sql"
  for f in ${PERF_EXTRA_SQL:-}; do log "  추가 SQL: $f"; psql_app "$ROOT_DIR/$f"; done

  log "3/8 notification-service·backend 기동"
  dc up -d --wait notification-service backend
  { echo 'SET ROLE application_user;'; cat "$ROOT_DIR/database/scripts/search-indexes.sql"; } | psql_db -q

  log "4/8 측정용 사용자 ${users}명 + 관리자 1명 생성"
  psql_db -q -c "INSERT INTO application.users (kakao_id, nickname, email, role, provider, created_at, updated_at)
      SELECT 990000000 + g, 'perf-user-' || g, 'perf' || g || '@example.invalid', 'USER', 'KAKAO', now(), now()
      FROM generate_series(1, $users) g ON CONFLICT (kakao_id) DO NOTHING;
    INSERT INTO application.users (kakao_id, nickname, email, role, provider, created_at, updated_at)
      VALUES (989999999, 'perf-admin', 'perf-admin@example.invalid', 'ADMIN', 'KAKAO', now(), now())
      ON CONFLICT (kakao_id) DO NOTHING;"
  { echo userId; psql_db -At -c "select id from application.users where kakao_id between 990000001 and $((990000000 + users)) order by id"; } > data/user_ids.csv
  psql_db -At -c "select id from application.users where kakao_id = 989999999" > data/admin_id.txt
  tools python seed/seed.py mint

  log "5/8 수집 (외부 API 목, 두 출처) → 정규화 완료 대기"
  tools python seed/seed.py wait
  tools python seed/seed.py collect YOUTH_POLICY EMPLOYMENT24
  wait_sql "select count(*) from application.raw_normalization_request where status = 'PENDING'" -eq 0 3600 >/dev/null \
    || die "정규화 대기 요청이 줄지 않습니다"
  log "  공고 수: $(wait_stable 'select count(*) from application.opportunities')"

  log "6/8 판정 프로필·관심사·저장 시드 (API)"
  tools python seed/seed.py profiles
  log "  판정 결과 행: $(wait_stable 'select count(*) from application.eligibility_set_results' 3600)"

  log "7/8 k6 데이터 풀 내보내기"
  export_pools

  log "8/8 VACUUM ANALYZE → 스냅샷 볼륨 저장"
  psql_db -q -c "VACUUM (ANALYZE);" -c "CHECKPOINT;"
  psql_db -At -F, -c "select 'opportunities', count(*) from application.opportunities union all
      select 'raw_external_record', count(*) from application.raw_external_record union all
      select 'users', count(*) from application.users union all
      select 'eligibility_set_results', count(*) from application.eligibility_set_results union all
      select 'user_opportunity_preferences', count(*) from application.user_opportunity_preferences" > data/seed_counts.csv
  cat data/seed_counts.csv
  dc stop -t 60 >/dev/null
  snapshot_save
  log "시드 완료. 이제 ./perf/run.sh smoke 로 확인하세요"
}

export_pools() {
  psql_db -At -c "select 'id,type' union all (select id || ',' || opportunity_type from application.opportunities
      where current_version_id is not null order by md5(id::text) limit 5000)" > data/opportunities.csv
  psql_db -At -c "select 'keyword' union all (select w from (select regexp_split_to_table(title, '\s+') w
      from application.opportunities) t where length(w) between 2 and 12 and w !~ '[][,\"()/]' group by w
      order by count(*) desc, w limit 200)" > data/keywords.csv
  psql_db -At -c "select 'code,level' union all (select region_code || ',' ||
      case when parent_code is null then 'sido' else 'leaf' end from application.regions order by region_code)" > data/regions.csv
  psql_db -At -c "select 'category,subCategory' union all (select distinct replace(category, ',', ' ') || ',' ||
      coalesce(replace(sub_category, ',', ' '), '') from application.opportunities where category is not null
      order by 1 limit 300)" > data/categories.csv
  wc -l data/*.csv | sed 's/^/  /'
}

snapshot_save() {
  docker volume rm -f "$SNAP_VOLUME" >/dev/null
  docker volume create "$SNAP_VOLUME" >/dev/null
  docker run --rm -v "$PG_VOLUME":/from:ro -v "$SNAP_VOLUME":/to alpine sh -c 'cp -a /from/. /to/'
}

snapshot_restore() {
  docker volume inspect "$SNAP_VOLUME" >/dev/null 2>&1 || die "스냅샷이 없습니다. ./perf/run.sh seed 를 먼저 실행하세요"
  docker volume rm -f "$PG_VOLUME" >/dev/null
  docker volume create "$PG_VOLUME" >/dev/null
  docker run --rm -v "$SNAP_VOLUME":/from:ro -v "$PG_VOLUME":/to alpine sh -c 'cp -a /from/. /to/'
  docker volume rm -f yon-perf-replica-data >/dev/null 2>&1 || true
}

# ------------------------------------------------------------------ 한 회차
scenario_file() {
  case $1 in
    perf) echo scenarios/perf-baseline.js ;;
    loadA) echo scenarios/load-mixed.js ;;
    loadB) echo scenarios/load-with-collection.js ;;
    probe) echo scenarios/load-probe.js ;;
    *) die "알 수 없는 시나리오: $1" ;;
  esac
}

start_samplers() {
  local dir=$1
  ( echo "ts,container,cpu_pct,mem_used,mem_limit,mem_pct"
    while :; do
      ts=$(date +%s)
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' 2>/dev/null \
        | grep '^yon-perf-' | sed -e "s/^/$ts,/" -e 's/%//g' -e 's| / |,|' || true
      sleep 1
    done ) > "$dir/docker_stats.csv" &
  echo $! > "$dir/.sampler_stats.pid"
  ( echo "ts,active,idle_in_tx,waiting_lock,total,pending_normalization,top_wait"
    while :; do
      psql_db -At -F, -c "select extract(epoch from now())::bigint,
          count(*) filter (where state = 'active' and backend_type = 'client backend'),
          count(*) filter (where state like 'idle in transaction%'),
          count(*) filter (where wait_event_type = 'Lock'),
          count(*) filter (where backend_type = 'client backend'),
          (select count(*) from application.raw_normalization_request where status = 'PENDING'),
          coalesce((select wait_event_type || ':' || wait_event from pg_stat_activity
             where state = 'active' and wait_event is not null and backend_type = 'client backend'
             group by 1 order by count(*) desc limit 1), '')
          from pg_stat_activity" 2>/dev/null || true
      sleep 2
    done ) > "$dir/pg_activity.csv" &
  echo $! > "$dir/.sampler_pg.pid"
}

stop_samplers() {
  local dir=$1 f
  for f in "$dir"/.sampler_*.pid; do
    [ -f "$f" ] && { kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; }
  done
}

cpu_stat() { # 컨테이너별 cgroup cpu.stat (스로틀 횟수·시간)
  local c
  for c in $(dc ps --format '{{.Name}}'); do
    echo "## $c"
    docker exec "$c" cat /sys/fs/cgroup/cpu.stat 2>/dev/null || true
  done
}

one_run() {
  local label=$1 scenario=$2 version=$3 idx=$4
  local dir="results/$label/$scenario/$version/run-$(printf %02d "$idx")"
  if [ -f "$dir/summary.json" ]; then log "건너뜀(이미 있음): $dir"; return 0; fi
  rm -rf "$dir"; mkdir -p "$dir/jfr"; chmod 777 "$dir" "$dir/jfr" 2>/dev/null || true
  export RUN_DIR="./$dir" PERF_TAG=$version MOCK_DAY=0 PERF_REGION_SYNC=false
  [ "$scenario" = "loadB" ] && export MOCK_DAY=1
  log "▶ $label / $scenario / $version / run $idx"

  dc down --remove-orphans >/dev/null 2>&1 || true
  snapshot_restore
  local t0; t0=$(date +%s)
  dc up -d --wait $(server_services) >/dev/null
  tools python seed/seed.py wait >/dev/null
  sleep "${SETTLE_SEC:-20}"  # 기동 직후 백그라운드 작업(대조·스케줄러 첫 실행)이 가라앉도록
  psql_db -q -c "select pg_stat_statements_reset();" -c "select pg_stat_reset();" >/dev/null
  psql_db -At -c "show jit" > "$dir/pg_jit.txt" 2>/dev/null || true
  cpu_stat > "$dir/cpu_stat_before.txt"
  start_samplers "$dir"
  local t1; t1=$(date +%s)

  local file rc=0
  file=$(scenario_file "$scenario")
  dc --profile tools run --rm -T k6 run --quiet --summary-export /out/k6-summary.json \
    --out csv=/out/k6.csv.gz ${K6_EXTRA_ARGS:-} \
    -e USERS="${USERS:-100}" -e SLO_READ_P95_MS="${SLO_READ_P95_MS:-500}" -e SLO_HEAVY_P95_MS="${SLO_HEAVY_P95_MS:-1000}" \
    -e MAIN_DUR="${MAIN_DUR_OVERRIDE:-}" "$file" > "$dir/k6.log" 2>&1 || rc=$?

  local t2; t2=$(date +%s)
  stop_samplers "$dir"
  cpu_stat > "$dir/cpu_stat_after.txt"
  psql_db -At -F$'\t' -c "select round(total_exec_time::numeric,1) total_ms, calls, round(mean_exec_time::numeric,2) mean_ms,
      round(max_exec_time::numeric,1) max_ms, rows, shared_blks_hit, shared_blks_read,
      round((blk_read_time + blk_write_time)::numeric,1) io_ms, regexp_replace(query, '\s+', ' ', 'g')
      from pg_stat_statements order by total_exec_time desc limit 60" > "$dir/pg_stat_statements.tsv" || true
  psql_db -At -F, -c "select relname, seq_scan, seq_tup_read, idx_scan, n_tup_ins, n_tup_upd, n_tup_del, n_dead_tup,
      autovacuum_count, autoanalyze_count from pg_stat_user_tables where schemaname = 'application'
      order by seq_tup_read desc" > "$dir/pg_tables.csv" || true
  for s in $(server_services); do dc logs --no-color "$s" > "$dir/$s.log" 2>&1 || true; done
  dc stop -t 60 >/dev/null 2>&1 || true   # JVM 정상 종료 → JFR 기록 저장

  local digests=""
  for svc in backend data-service notification-service; do
    digests+="\"$svc\": \"$(docker image inspect --format '{{.Id}} {{index .Config.Labels "yon.perf.git-sha"}}' "yon-$svc:$version" 2>/dev/null)\", "
  done
  cat > "$dir/meta.json" <<EOF
{"label": "$label", "scenario": "$scenario", "version": "$version", "run": $idx, "k6_exit": $rc,
 "boot_started": $t0, "k6_started": $t1, "k6_ended": $t2, "users": ${USERS:-100}, "replica": "${PERF_REPLICA:-0}",
 "profile": "${PERF_PROFILE:-}", "host_ncpu": $(docker info --format '{{.NCPU}}'),
 "images": {${digests%, }}}
EOF
  tools python analysis/summarize.py "$dir" > "$dir/summarize.log" 2>&1 || log "  집계 실패: $dir/summarize.log"
  [ "$rc" -eq 0 ] || log "  k6 종료 코드 $rc (기준값 미달 포함). 기록은 그대로 남깁니다"
  log "  완료 $((t2 - t1))초 → $dir"
}

# ------------------------------------------------------------------ bench
cmd_bench() {
  local label versions scenarios runs probe_runs
  label=$(date +%Y%m%d-%H%M); versions=${PERF_TAG:-base}; scenarios="perf,loadA,loadB"; runs=$RUNS; probe_runs=3
  while [ $# -gt 0 ]; do
    case $1 in
      --label) label=$2; shift 2 ;;
      --versions) versions=$2; shift 2 ;;
      --scenarios) scenarios=$2; shift 2 ;;
      --runs) runs=$2; shift 2 ;;
      --probe-runs) probe_runs=$2; shift 2 ;;
      --profile) export PERF_PROFILE=$2; shift 2 ;;
      *) die "알 수 없는 옵션: $1" ;;
    esac
  done
  IFS=, read -r -a vlist <<< "$versions"
  IFS=, read -r -a slist <<< "$scenarios"
  for v in "${vlist[@]}"; do docker image inspect "yon-backend:$v" >/dev/null 2>&1 || die "이미지 yon-backend:$v 가 없습니다 (./perf/run.sh build $v)"; done
  mkdir -p "results/$label"
  [ -f "results/$label/.versions" ] || echo "$versions" > "results/$label/.versions"
  log "bench label=$label versions=${vlist[*]} scenarios=${slist[*]} runs=$runs (probe $probe_runs)"
  local max=$runs; [ "$probe_runs" -gt "$max" ] && max=$probe_runs
  for ((i = 1; i <= max; i++)); do
    for s in "${slist[@]}"; do
      local n=$runs; [ "$s" = "probe" ] && n=$probe_runs
      [ "$i" -le "$n" ] || continue
      # 회차마다 버전 순서를 뒤집는다 (1회차 A→B, 2회차 B→A …)
      local order=("${vlist[@]}")
      if [ $((i % 2)) -eq 0 ]; then order=(); for ((k = ${#vlist[@]} - 1; k >= 0; k--)); do order+=("${vlist[k]}"); done; fi
      for v in "${order[@]}"; do one_run "$label" "$s" "$v" "$i"; done
    done
  done
  dc down --remove-orphans >/dev/null 2>&1 || true
  cmd_report "$label"
}

cmd_smoke() {
  # 모든 API를 한 번씩 거치도록 perf 는 API 12개를 10초씩, 나머지는 길이만 줄인다
  export WARMUP_DUR=20s RAMP_DUR=20s DOWN_DUR=10s PERF_DUR=10s STEP_DUR=30s COLLECT_DELAY=20s
  export MAIN_DUR_OVERRIDE=1m PROBE_STEPS=100,200
  export K6_EXTRA_ARGS="-e WARMUP_DUR=$WARMUP_DUR -e RAMP_DUR=$RAMP_DUR -e DOWN_DUR=$DOWN_DUR -e PERF_DUR=$PERF_DUR -e STEP_DUR=$STEP_DUR -e COLLECT_DELAY=$COLLECT_DELAY -e PROBE_STEPS=$PROBE_STEPS"
  rm -rf results/smoke
  for s in perf loadA probe loadB; do one_run smoke "$s" "${PERF_TAG:?}" 1; done
  dc down --remove-orphans >/dev/null 2>&1 || true
  cmd_report smoke
}

cmd_explain() { # 스냅샷 DB로 서버를 띄우고 느린 API를 몇 번 불러 실제 실행 계획(EXPLAIN ANALYZE)을 남긴다
  local out="results/${1:-before}/explain"
  mkdir -p "$out" results/_scratch/jfr && chmod 777 results/_scratch/jfr 2>/dev/null || true
  export RUN_DIR=./results/_scratch MOCK_DAY=0 PERF_REGION_SYNC=false
  log "실행 계획 수집: 스냅샷 복원 → 서버 기동"
  dc down --remove-orphans >/dev/null 2>&1 || true
  snapshot_restore
  dc up -d --wait $(server_services) >/dev/null
  tools python seed/seed.py wait >/dev/null
  psql_db -q -c "ALTER SYSTEM SET auto_explain.log_analyze = on" -c "ALTER SYSTEM SET auto_explain.log_buffers = on" \
    -c "ALTER SYSTEM SET auto_explain.log_timing = on" -c "ALTER SYSTEM SET auto_explain.log_min_duration = 50" \
    -c "ALTER SYSTEM SET auto_explain.log_format = text" -c "SELECT pg_reload_conf()" >/dev/null
  tools python seed/seed.py explain-calls | tee "$out/calls.txt"
  sleep 3
  dc logs --no-color db > "$out/db-explain.log" 2>&1
  psql_db -At -c "show work_mem" -c "show shared_buffers" -c "show max_parallel_workers_per_gather" > "$out/pg-settings.txt"
  dc down --remove-orphans >/dev/null 2>&1 || true
  log "실행 계획: perf/$out/db-explain.log"
}

cmd_report() {
  local label=${1:?label}
  tools python analysis/report.py "results/$label"
  log "보고서: perf/results/$label/report.md"
}

case ${1:-} in
  init) shift; cmd_init "$@" ;;
  build) shift; cmd_build "$@" ;;
  seed) shift; cmd_seed "$@" ;;
  smoke) shift; cmd_smoke "$@" ;;
  bench) shift; cmd_bench "$@" ;;
  report) shift; cmd_report "$@" ;;
  explain) shift; cmd_explain "$@" ;;
  down) dc down --remove-orphans ;;
  *) sed -n '2,20p' "$0"; exit 1 ;;
esac
