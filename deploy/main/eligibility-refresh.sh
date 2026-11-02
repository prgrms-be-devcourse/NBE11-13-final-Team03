#!/usr/bin/env bash
# 메인 서버에서 판정 배치를 즉시 실행한다. 내부 키는 배포 디렉터리 .env에서 읽고 명령줄에 남기지 않는다.
# 사용: ssh yon-main 'bash ~/yon/eligibility-refresh.sh'
set -euo pipefail

ENV_FILE="${ENV_FILE:-$HOME/yon/.env}"
URL="${URL:-http://localhost:8080/api/internal/eligibility/batch/refresh}"

key="$(grep -E '^INTERNAL_API_KEY=' "$ENV_FILE" | tail -n 1 | cut -d= -f2-)"
if [ -z "$key" ]; then
  echo "INTERNAL_API_KEY not found in $ENV_FILE" >&2
  exit 1
fi

printf 'X-INTERNAL-KEY: %s\n' "$key" \
  | curl -sS --max-time 600 -X POST -H @- -w '\nHTTP %{http_code}\n' "$URL"
