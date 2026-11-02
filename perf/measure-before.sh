#!/usr/bin/env bash
# 개선 전 측정을 한 번에 실행한다: 환경 점검 → 이미지 빌드 → 측정용 DB 시드 → 스모크(통과해야 진행) → 반복 측정 → 결과 문서.
#
#   bash perf/measure-before.sh              기본: 시나리오 4종(perf, loadA, loadB 각 7회 + probe 3회), 약 10~11시간
#   bash perf/measure-before.sh --runs 5     회차를 5회로 (약 8시간)
#   bash perf/measure-before.sh --smoke-only 스모크까지만 (약 1~1.5시간: 빌드·시드 포함)
#   bash perf/measure-before.sh --reseed     측정용 DB를 새로 만든다 (기본은 있으면 재사용)
#   bash perf/measure-before.sh --rebuild    이미지를 다시 빌드한다 (기본은 같은 커밋 이미지가 있으면 재사용)
#
# 중간에 멈추면 같은 명령을 다시 실행한다. 끝난 회차는 건너뛴다.
# 결과 문서: perf/results/before/RESULT.md  (콘솔 기록: perf/results/before/console.log)
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PERF_DIR/.." && pwd)"
cd "$PERF_DIR"

LABEL=before
TAG=base
RUNS_OPT=""
SMOKE_ONLY=0
RESEED=0
REBUILD=0
SCENARIOS="perf,loadA,loadB,probe"
while [ $# -gt 0 ]; do
  case $1 in
    --runs) RUNS_OPT=$2; shift 2 ;;
    --smoke-only) SMOKE_ONLY=1; shift ;;
    --reseed) RESEED=1; shift ;;
    --rebuild) REBUILD=1; shift ;;
    --scenarios) SCENARIOS=$2; shift 2 ;;
    --label) LABEL=$2; shift 2 ;;
    --tag) TAG=$2; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1"; exit 1 ;;
  esac
done

mkdir -p "results/$LABEL"
exec > >(tee -a "results/$LABEL/console.log") 2>&1
log() { printf '\n[%s] ==== %s\n' "$(date '+%m-%d %H:%M:%S')" "$*"; }
fail() {
  printf '\n[%s] 중단: %s\n' "$(date '+%H:%M:%S')" "$*"
  printf '이 메시지와 perf/results/%s/console.log 를 Claude에게 보내 주세요.\n' "$LABEL"
  exit 1
}
trap 'fail "예상하지 못한 오류 (줄 $LINENO)"' ERR

STARTED=$(date +%s)
log "Y:ON 개선 전 측정 시작 (label=$LABEL, tag=$TAG)"
echo "  PC 절전 모드를 끄고 실행하세요 (설정 > 전원 > 화면·절전 '안 함'). 절전되면 측정이 멈춥니다."

# ---------------------------------------------------------------- 0. 코드 기준 확인
branch=$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)
sha=$(git -C "$ROOT_DIR" rev-parse --short HEAD)
git -C "$ROOT_DIR" fetch -q origin main 2>/dev/null || echo "  (origin 조회 실패: 네트워크 확인. 계속 진행)"
remote=$(git -C "$ROOT_DIR" rev-parse --short origin/main 2>/dev/null || echo "?")
echo "  코드: $branch @ $sha (origin/main=$remote)"
if [ "$sha" != "$remote" ]; then
  echo "  주의: 지금 코드가 origin/main 과 다릅니다. 개선 전 기준은 최신 main 이어야 합니다."
  echo "        git switch main && git pull --ff-only  후 다시 실행하거나, 의도한 것이면 그대로 두세요."
fi
if ! git -C "$ROOT_DIR" diff --quiet --ignore-cr-at-eol HEAD -- backend data-service notification-service contracts >/dev/null 2>&1; then
  echo "  주의: 서버 코드에 커밋하지 않은 변경이 있습니다. 개선 전 측정에는 깨끗한 main 을 권장합니다."
fi

# ---------------------------------------------------------------- 1. 환경 점검
log "1/6 환경 점검 (Docker, 코어·메모리, 설정 파일)"
bash ./run.sh init || fail "환경 점검 실패. 위 메시지대로 Docker Desktop/.wslconfig 를 조정한 뒤 다시 실행하세요"
set -a; . ./perf.env; set +a
export PERF_TAG=$TAG
[ -n "$RUNS_OPT" ] && export RUNS=$RUNS_OPT

# ---------------------------------------------------------------- 2. 이미지
log "2/6 서버 이미지 ($TAG)"
existing=$(docker image inspect --format '{{index .Config.Labels "yon.perf.git-sha"}}' "yon-backend:$TAG" 2>/dev/null || true)
if [ "$REBUILD" = "1" ] || [ "$existing" != "$sha" ]; then
  bash ./run.sh build "$TAG" || fail "이미지 빌드 실패"
else
  echo "  같은 커밋($sha) 이미지가 있어 재사용합니다 (--rebuild 로 다시 빌드)"
fi

# ---------------------------------------------------------------- 3. 시드
log "3/6 측정용 DB 스냅샷"
if [ "$RESEED" = "1" ] || ! docker volume inspect yon-perf-pgsnap >/dev/null 2>&1; then
  echo "  수집 14만 건·정규화·사용자 1,000명 시드 (약 20~40분)"
  bash ./run.sh seed || fail "시드 실패. 'cd perf && docker compose --env-file secrets.env --env-file perf.env -f compose.perf.yaml logs data-service backend' 출력도 함께 보내 주세요"
else
  echo "  기존 스냅샷 재사용 (--reseed 로 새로 만들기)"
  [ -f data/seed_counts.csv ] && sed 's/^/    /' data/seed_counts.csv
fi

# ---------------------------------------------------------------- 4. 스모크
log "4/6 스모크 (시나리오 4종을 짧게, 약 25분)"
if [ "$SMOKE_ONLY" != "1" ] && [ "$(cat "results/.smoke-passed-$TAG" 2>/dev/null)" = "$sha" ]; then
  echo "  같은 커밋($sha)으로 스모크를 이미 통과했습니다. 건너뜁니다"
else
  rm -f "results/.smoke-passed-$TAG"
  bash ./run.sh smoke || fail "스모크 실행 실패"
  docker compose --env-file secrets.env --env-file perf.env -f compose.perf.yaml --profile tools run --rm -T tools \
    python analysis/check_smoke.py results/smoke || fail "스모크 점검 실패 (위 표 참고). 반복 측정은 시작하지 않았습니다"
  echo "$sha" > "results/.smoke-passed-$TAG"
fi

if [ "$SMOKE_ONLY" = "1" ]; then
  log "스모크까지 완료 (--smoke-only). 결과: perf/results/smoke/report.md"
  exit 0
fi

# ---------------------------------------------------------------- 5. 반복 측정
log "5/6 반복 측정: 시나리오 $SCENARIOS, 회차 ${RUNS:-7} (probe 3회)"
docker info --format '{"ncpu": {{.NCPU}}, "mem_bytes": {{.MemTotal}}, "docker": "{{.ServerVersion}}", "os": "{{.OperatingSystem}}"}' \
  > "results/$LABEL/host.json" 2>/dev/null || true
{
  echo "{\"branch\": \"$branch\", \"sha\": \"$sha\", \"origin_main\": \"$remote\", \"tag\": \"$TAG\","
  echo " \"runs\": ${RUNS:-7}, \"scenarios\": \"$SCENARIOS\", \"users\": ${USERS:-100},"
  echo " \"slo_read_ms\": ${SLO_READ_P95_MS:-500}, \"slo_heavy_ms\": ${SLO_HEAVY_P95_MS:-1000},"
  echo " \"cpuset\": {\"main\": \"$CPUSET_MAIN\", \"data\": \"$CPUSET_DATA\", \"db\": \"$CPUSET_DB\", \"tools\": \"$CPUSET_TOOLS\"},"
  echo " \"replica\": \"${PERF_REPLICA:-0}\", \"started\": $STARTED}"
} > "results/$LABEL/plan.json"
bash ./run.sh bench --label "$LABEL" --versions "$TAG" --scenarios "$SCENARIOS" --runs "${RUNS:-7}" --probe-runs 3 \
  || fail "반복 측정 중 오류"

# ---------------------------------------------------------------- 6. 결과 문서
log "6/6 결과 문서"
docker compose --env-file secrets.env --env-file perf.env -f compose.perf.yaml --profile tools run --rm -T tools \
  python analysis/result_doc.py "results/$LABEL" || fail "결과 문서 생성 실패"

trap - ERR
elapsed=$(( $(date +%s) - STARTED ))
log "완료 (소요 $((elapsed / 3600))시간 $(((elapsed % 3600) / 60))분)"
echo "  결과 문서: perf/results/$LABEL/RESULT.md"
echo "  Claude에게 '완료'라고 보내 주세요. 결과 폴더를 직접 읽어 병목 분석을 이어서 합니다."
