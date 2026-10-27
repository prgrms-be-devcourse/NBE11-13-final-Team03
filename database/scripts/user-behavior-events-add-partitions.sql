-- user_behavior_events 월 파티션 추가 (월 점검용, 반복 실행 가능). 실행 계정: application_user.
-- KST 이번 달 + 12개월까지 없는 달만 만든다. 기존 파티션은 건너뛰고 동명 일반 테이블은 거부한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO application, pg_catalog;
-- DEFAULT에 해당 월 데이터가 있으면 전체 트랜잭션이 실패한다. 복구 절차는 운영 문서를 따른다.
LOCK TABLE user_behavior_events IN ACCESS EXCLUSIVE MODE;
DO $$
DECLARE
    last_month date := (date_trunc('month', now() AT TIME ZONE 'Asia/Seoul') + interval '12 months')::date;
    m           date := date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')::date;
    partition_name text;
BEGIN
    WHILE m <= last_month LOOP
        partition_name := 'user_behavior_events_' || to_char(m, 'YYYY_MM');
        IF to_regclass(partition_name) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF user_behavior_events FOR VALUES FROM (%L) TO (%L)',
                           partition_name, m, (m + interval '1 month')::date);
        ELSIF NOT EXISTS (SELECT 1 FROM pg_inherits
                          WHERE inhrelid=to_regclass(partition_name) AND inhparent='user_behavior_events'::regclass) THEN
            RAISE EXCEPTION '% exists but is not a user_behavior_events partition', partition_name;
        END IF;
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;
COMMIT;
