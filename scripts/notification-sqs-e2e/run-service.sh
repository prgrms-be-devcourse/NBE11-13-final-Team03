#!/bin/sh
# Host Java 25 + built Boot JARs. No .env is read or written. All credentials are local test placeholders.
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
task_root=$(CDPATH= cd -- "$task_dir/../.." && pwd)
task_role=${1:-}
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_EC2_METADATA_DISABLED=true
unset AWS_SESSION_TOKEN AWS_PROFILE
export KAKAO_CLIENT_ID=e2e-placeholder KAKAO_CLIENT_SECRET=e2e-placeholder
export INTERNAL_API_KEY=e2e-internal-only DATA_SERVICE_INTERNAL_API_KEY=e2e-data-only
export JWT_SECRET_KEY=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==
case "$task_role" in
  backend)
    task_jar="$task_root/backend/build/libs/app.jar"
    export APPLICATION_DB_PASSWORD=e2e-application-only
    set -- --server.port=18080 --spring.datasource.username=application_user \
      --spring.datasource.password=e2e-application-only \
      --yon.notification.publisher.enabled=true --yon.notification.publisher.transport=sqs \
      --yon.notification.facade.enabled=false --yon.tracking.date.enabled=false \
      --yon.tracking.fanout.enabled=false --yon.eligibility.batch.enabled=false \
      --yon.data-service.base-url=http://127.0.0.1:18082
    ;;
  notification)
    task_jar="$task_root/notification-service/build/libs/notification-service.jar"
    export NOTIFICATION_DB_PASSWORD=e2e-notification-only
    set -- --server.port=18081 --spring.datasource.username=notification_user \
      --spring.datasource.password=e2e-notification-only \
      --yon.notification.sqs.consumer-enabled=true --yon.notification.receiver.enabled=false \
      --yon.notification.user-api.enabled=false
    ;;
  *) echo 'Usage: sh scripts/notification-sqs-e2e/run-service.sh backend|notification' >&2; exit 2 ;;
esac
if [ ! -f "$task_jar" ]; then echo "Build the Boot JAR first: $task_jar" >&2; exit 1; fi
exec java -jar "$task_jar" "$@" \
  --spring.profiles.active=database-boundary \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/yon_sqs_e2e \
  --app.scheduling.enabled=true --yon.notification.push.enabled=false \
  --yon.notification.sqs.region=ap-northeast-2 \
  --yon.notification.sqs.endpoint=http://127.0.0.1:14566 \
  --yon.notification.sqs.queue-url=http://127.0.0.1:14566/000000000000/yon-sqs-e2e-notifications \
  --yon.notification.sqs.dlq-url=http://127.0.0.1:14566/000000000000/yon-sqs-e2e-notifications-dlq
