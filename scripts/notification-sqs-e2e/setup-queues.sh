#!/bin/sh
# awslocal runs INSIDE this disposable project; never invokes a host AWS CLI.
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
docker compose -f "$task_dir/compose.yaml" -p yon-sqs-e2e exec -T sqs \
  awslocal sqs create-queue --region ap-northeast-2 --queue-name yon-sqs-e2e-notifications-dlq \
  --attributes file:///e2e/dlq-attributes.json
docker compose -f "$task_dir/compose.yaml" -p yon-sqs-e2e exec -T sqs \
  awslocal sqs create-queue --region ap-northeast-2 --queue-name yon-sqs-e2e-notifications \
  --attributes file:///e2e/source-attributes.json
