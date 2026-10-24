#!/bin/bash
# Builds the three Boot JARs (tests skipped) and starts the whole local stack.
# Usage: ./start.sh              build JARs and images, then start
#        ./start.sh --no-build   reuse existing JARs/images
# Stop:  docker compose down     (add -v to delete the local DB volume)
set -e

cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "docker/.env가 없습니다. .env.example을 복사해 값을 채운 뒤 다시 실행하세요."
    exit 1
fi

BUILD="--build"
if [ "$1" = "--no-build" ]; then
    BUILD=""
else
    echo "[1/2] Boot JAR 빌드 (테스트 제외)"
    (cd ../backend && ./gradlew bootJar -x test -q)
    (cd ../data-service && ./gradlew bootJar -x test -q)
    (cd ../notification-service && ./gradlew bootJar -x test -q)
fi

echo "[2/2] 컨테이너 기동"
docker compose up -d $BUILD --wait

echo
docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"
