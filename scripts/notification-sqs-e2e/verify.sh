#!/bin/sh
# Read-only checks, each schema through its owner account. No cross-schema reads.
set -eu
task_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
docker compose -f "$task_dir/compose.yaml" -p yon-sqs-e2e exec -T db \
  env PGPASSWORD=e2e-application-only psql -h 127.0.0.1 -U application_user -d yon_sqs_e2e -P pager=off -v ON_ERROR_STOP=1 <<'SQL'
SELECT message_id, status, retry_count, last_error, published_at
FROM application.outbox_events WHERE message_id = '90000000-0000-4000-8000-000000000001';
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM application.outbox_events
    WHERE message_id = '90000000-0000-4000-8000-000000000001' AND status = 'PUBLISHED') THEN
    RAISE EXCEPTION 'Outbox is not PUBLISHED yet; inspect Backend or retry shortly';
  END IF;
END $$;
SQL
docker compose -f "$task_dir/compose.yaml" -p yon-sqs-e2e exec -T db \
  env PGPASSWORD=e2e-notification-only psql -h 127.0.0.1 -U notification_user -d yon_sqs_e2e -P pager=off -v ON_ERROR_STOP=1 <<'SQL'
SELECT message_id, user_id, result, notification_id FROM notification.consumed_messages
WHERE message_id = '90000000-0000-4000-8000-000000000001';
SELECT id, user_id, message_id, type, in_app_visible FROM notification.notifications
WHERE message_id = '90000000-0000-4000-8000-000000000001';
DO $$ BEGIN
  IF (SELECT count(*) FROM notification.consumed_messages
      WHERE message_id = '90000000-0000-4000-8000-000000000001' AND result = 'CREATED') <> 1
    OR (SELECT count(*) FROM notification.notifications
      WHERE message_id = '90000000-0000-4000-8000-000000000001') <> 1 THEN
    RAISE EXCEPTION 'Receipt/notification is not exactly one yet; inspect Notification or retry shortly';
  END IF;
END $$;
SQL
echo 'E2E DB checks passed. Source Visible/NotVisible should settle at 0.'
docker compose -f "$task_dir/compose.yaml" -p yon-sqs-e2e exec -T sqs \
  awslocal sqs get-queue-attributes --region ap-northeast-2 \
  --queue-url http://localhost:4566/000000000000/yon-sqs-e2e-notifications \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
