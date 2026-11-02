#!/usr/bin/env bash
# 단축 측정 (약 3~3.5시간): 점검 → 빌드(있으면 재사용) → 시드(있으면 재사용) → 스모크(통과 기록 있으면 건너뜀)
#   → perf·loadA·loadB 각 5회 + probe 1회 → 결과 문서 perf/results/before/RESULT.md
#
#   bash perf/measure-quick.sh                 기본
#   bash perf/measure-quick.sh --skip-loadB    수집 동시 시나리오 제외 (약 1시간 단축)
#
# 측정 구간을 줄였다: API 단독 20초, 부하 A 측정 3분, 부하 B 측정 6분, 한계 탐색 단계당 1분.
# 중간에 멈추면 같은 명령을 다시 실행한다. 끝난 단계·회차는 건너뛴다. 한 회차가 실패해도 다음으로 넘어간다.
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PERF_DIR/.." && pwd)"
cd "$PERF_DIR"

LABEL=before; TAG=base; N_RUNS=5; SKIP_B=0
[ "${1:-}" = "--skip-loadB" ] && SKIP_B=1

mkdir -p "results/$LABEL"
exec > >(tee -a "results/$LABEL/console.log") 2>&1
log() { printf '\n[%s] ==== %s\n' "$(date '+%m-%d %H:%M:%S')" "$*"; }
fail() { printf '\n[%s] 중단: %s\n이 메시지와 perf/results/%s/console.log 를 Claude에게 보내 주세요.\n' "$(date '+%H:%M:%S')" "$*" "$LABEL"; exit 1; }
STARTED=$(date +%s)
sha=$(git -C "$ROOT_DIR" rev-parse --short HEAD)
log "단축 측정 시작 (코드 $sha, 회차 $N_RUNS)"

log "1/5 환경 점검"
bash ./run.sh init || fail "환경 점검 실패 (위 메시지 참고)"
export PERF_TAG=$TAG SETTLE_SEC=10

log "2/5 서버 이미지"
label=$(docker image inspect --format '{{index .Config.Labels "yon.perf.git-sha"}}' "yon-notification-service:$TAG" 2>/dev/null || true)
if [ "${label%-dirty}" = "$sha" ]; then
  echo "  같은 커밋($sha) 이미지 재사용"
else
  bash ./run.sh build "$TAG" || fail "이미지 빌드 실패"
fi

log "3/5 측정용 DB (약 20~40분, 있으면 재사용)"
if ! docker volume inspect yon-perf-pgsnap >/dev/null 2>&1; then
  bash ./run.sh seed || fail "시드 실패"
else
  echo "  기존 스냅샷 재사용"
fi

log "4/5 스모크 (약 20분, 통과 기록 있으면 건너뜀)"
if [ "$(cat "results/.smoke-passed-$TAG" 2>/dev/null)" = "$sha" ]; then
  echo "  이미 통과"
else
  bash ./run.sh smoke || fail "스모크 실행 실패"
  docker compose --env-file secrets.env --env-file perf.env -f compose.perf.yaml --profile tools run --rm -T tools \
    python analysis/check_smoke.py results/smoke || fail "스모크 점검 실패 (위 표 참고)"
  echo "$sha" > "results/.smoke-passed-$TAG"
fi

log "5/5 반복 측정"
docker info --format '{"ncpu": {{.NCPU}}, "mem_bytes": {{.MemTotal}}, "docker": "{{.ServerVersion}}", "os": "{{.OperatingSystem}}"}' \
  > "results/$LABEL/host.json" 2>/dev/null || true
set -a; . ./perf.env; set +a
cat > "results/$LABEL/plan.json" <<EOF
{"branch": "$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)", "sha": "$sha", "origin_main": "$(git -C "$ROOT_DIR" rev-parse --short origin/main 2>/dev/null)",
 "tag": "$TAG", "runs": $N_RUNS, "mode": "quick (API 단독 20초, 부하 A 3분, 부하 B 6분, 한계 탐색 10~100명 단계 1분)",
 "users": ${USERS:-100}, "slo_read_ms": ${SLO_READ_P95_MS:-500}, "slo_heavy_ms": ${SLO_HEAVY_P95_MS:-1000},
 "cpuset": {"main": "$CPUSET_MAIN", "data": "$CPUSET_DATA", "db": "$CPUSET_DB", "tools": "$CPUSET_TOOLS"},
 "replica": "${PERF_REPLICA:-0}", "started": $STARTED}
EOF
export PERF_TAG=$TAG SETTLE_SEC=10
# 한계 탐색: 스모크에서 동시 100명 상당도 커넥션 풀이 고갈돼, 10명부터 올려 기준값을 지키는 지점을 찾는다
export K6_EXTRA_ARGS="-e WARMUP_DUR=1m -e RAMP_DUR=1m -e DOWN_DUR=30s -e PERF_DUR=20s -e STEP_DUR=1m -e COLLECT_DELAY=1m -e PROBE_STEPS=10,20,30,50,70,100"
bench() { # scenario main_dur runs
  MAIN_DUR_OVERRIDE=$2 bash ./run.sh bench --label "$LABEL" --versions "$TAG" --scenarios "$1" --runs "$3" --probe-runs "$3" \
    || echo "  [$1] 일부 회차 실패. 다음 시나리오로 넘어갑니다 (다시 실행하면 빠진 회차만 돈다)"
}
bench perf "" "$N_RUNS"
bench loadA 3m "$N_RUNS"
[ "$SKIP_B" = "1" ] || bench loadB 6m "$N_RUNS"
bench probe "" 1

log "결과 문서"
docker compose --env-file secrets.env --env-file perf.env -f compose.perf.yaml --profile tools run --rm -T tools \
  python analysis/result_doc.py "results/$LABEL" || fail "결과 문서 생성 실패"
elapsed=$(( $(date +%s) - STARTED ))
log "완료 (소요 $((elapsed / 3600))시간 $(((elapsed % 3600) / 60))분)"
echo "  결과 문서: perf/results/$LABEL/RESULT.md"
echo "  Claude에게 '완료'라고 보내 주세요."
