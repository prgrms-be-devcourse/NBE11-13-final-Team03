#!/usr/bin/env bash
# 메인 서버에서 판정 배치를 즉시 실행한다. 내부 키는 배포 디렉터리 .env에서 읽고 명령줄에 남기지 않는다.
# 자동 배포가 docker-compose.yml과 함께 이 파일을 배포 디렉터리(~/yon)에 올린다.
# 사용: ssh yon-main 'bash ~/yon/eligibility-refresh.sh'
# 종료 코드: 0 완료 또는 이미 실행 중이라 건너뜀(409), 1 그 밖의 실패(키 없음, 401, 503, 연결 실패 등)
set -euo pipefail

ENV_FILE="${ENV_FILE:-$HOME/yon/.env}"
URL="${URL:-http://localhost:8080/api/internal/eligibility/batch/refresh}"

if [ ! -r "$ENV_FILE" ]; then
  echo "cannot read $ENV_FILE" >&2
  exit 1
fi
key="$( { grep -E '^INTERNAL_API_KEY=' "$ENV_FILE" || true; } | tail -n 1 | cut -d= -f2-)"
if [ -z "$key" ]; then
  echo "INTERNAL_API_KEY not found in $ENV_FILE" >&2
  exit 1
fi

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
status="$(printf 'X-INTERNAL-KEY: %s\n' "$key" \
  | curl -sS --max-time 600 -X POST -H @- -o "$body" -w '%{http_code}' "$URL" || true)"
cat "$body"
echo
echo "HTTP $status"

case "$status" in
  200) exit 0 ;;
  409) echo "already running (scheduled batch in progress), skipped" >&2; exit 0 ;;
  *) exit 1 ;;
esac
