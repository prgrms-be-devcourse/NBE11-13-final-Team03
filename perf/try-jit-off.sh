#!/usr/bin/env bash
# 실험: DB의 JIT 만 끄고 개선 전과 같은 조건으로 측정한다 (약 35분). 앱 코드·이미지·데이터는 개선 전과 같다.
#   bash perf/try-jit-off.sh
# 결과: perf/results/jit-off/RESULT.md  (개선 전: perf/results/before/RESULT.md)
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PERF_DIR/.." && pwd)"
cd "$PERF_DIR"
LABEL=jit-off
mkdir -p "results/$LABEL"
exec > >(tee -a "results/$LABEL/console.log") 2>&1
STARTED=$(date +%s)
echo "[$(date '+%H:%M:%S')] JIT 끄기 실험 시작 (약 35분)"

set -a; . ./perf.env; set +a
export PERF_TAG=base PERF_DB_JIT=off SETTLE_SEC=10
export K6_EXTRA_ARGS="-e WARMUP_DUR=1m -e RAMP_DUR=1m -e DOWN_DUR=30s -e PERF_DUR=20s -e STEP_DUR=1m -e PROBE_STEPS=10,20,30,50,70,100 -e PERF_ENDPOINTS=recommendations,search_keyword,eligibility_list,search_filter"
cp results/before/host.json "results/$LABEL/" 2>/dev/null || true
cat > "results/$LABEL/plan.json" <<EOF
{"branch": "$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)", "sha": "$(git -C "$ROOT_DIR" rev-parse --short HEAD)", "origin_main": "$(git -C "$ROOT_DIR" rev-parse --short origin/main 2>/dev/null)",
 "tag": "base", "runs": 2, "mode": "실험: DB jit=off (그 외 개선 전과 동일). API 단독 4종 20초, 부하 A 3분, 한계 탐색 10~100명 단계 1분",
 "users": ${USERS:-100}, "slo_read_ms": ${SLO_READ_P95_MS:-500}, "slo_heavy_ms": ${SLO_HEAVY_P95_MS:-1000},
 "cpuset": {"main": "$CPUSET_MAIN", "data": "$CPUSET_DATA", "db": "$CPUSET_DB", "tools": "$CPUSET_TOOLS"},
 "replica": "${PERF_REPLICA:-0}", "started": $STARTED}
EOF
MAIN_DUR_OVERRIDE=3m bash ./run.sh bench --label "$LABEL" --versions base --scenarios perf,loadA,probe --runs 2 --probe-runs 1 \
  || echo "일부 회차 실패 (다시 실행하면 빠진 회차만 돈다)"
echo "JIT 설정 확인: $(cat results/$LABEL/loadA/base/run-01/pg_jit.txt 2>/dev/null)"
docker compose --env-file secrets.env --env-file perf.env -f compose.perf.yaml --profile tools run --rm -T tools \
  python analysis/result_doc.py "results/$LABEL"
echo "[$(date '+%H:%M:%S')] 완료 ($(( ($(date +%s) - STARTED) / 60 ))분). 결과: perf/results/$LABEL/RESULT.md — Claude에게 '완료'라고 보내 주세요."
