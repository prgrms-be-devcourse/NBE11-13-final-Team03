-- Dedicated E2E DB only. Starts at committed Outbox, not date detection/fan-out.
BEGIN;
SET LOCAL search_path TO application, pg_catalog;
DO $$ BEGIN
  IF current_database() <> 'yon_sqs_e2e' OR current_user <> 'application_user' THEN
    RAISE EXCEPTION 'Use only yon_sqs_e2e as application_user';
  END IF;
END $$;
INSERT INTO outbox_events (
  message_id, dedup_key, event_type, user_id, source_event_id, opportunity_id, notification_type,
  payload, status, retry_count, next_retry_at, created_at
) VALUES (
  '90000000-0000-4000-8000-000000000001',
  'NOTIFICATION_REQUESTED:90000000-0000-4000-8000-000000000002:900001',
  'NOTIFICATION_REQUESTED', 900001, '90000000-0000-4000-8000-000000000002', 900001, 'DEADLINE_SOON',
  jsonb_build_object(
    'schemaVersion', 1, 'eventType', 'NOTIFICATION_REQUESTED',
    'messageId', '90000000-0000-4000-8000-000000000001',
    'sourceEventId', '90000000-0000-4000-8000-000000000002',
    'correlationId', '90000000-0000-4000-8000-000000000003',
    'causationId', '90000000-0000-4000-8000-000000000002',
    'userId', 900001, 'opportunityId', 900001, 'opportunityVersionId', 900001,
    'type', 'DEADLINE_SOON', 'channels', jsonb_build_array('IN_APP'),
    'title', '[LOCAL SQS E2E] 알림 전달 검증', 'content', '독립 JAR 두 서버의 SQS 전달 검증입니다.',
    'link', '/opportunities/900001', 'occurredAt', CURRENT_TIMESTAMP,
    'expiresAt', CURRENT_TIMESTAMP + interval '1 day', 'payload', jsonb_build_object('threshold', 'D-3')
  ), 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
) ON CONFLICT (message_id) DO NOTHING;
COMMIT;
SELECT message_id, status, retry_count, published_at FROM application.outbox_events
WHERE message_id = '90000000-0000-4000-8000-000000000001';
