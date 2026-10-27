-- 행동 로그를 received_at (timestamp without time zone) 월별 RANGE 파티션으로 교체한다.
-- application_user 소유로, backend 중지·백업 후 한 번 실행한다.
-- backend의 PARTITIONED TABLE 인식 설정을 먼저 배포한다. 기동 시 자동 실행하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO application, pg_catalog;
LOCK TABLE user_behavior_events IN ACCESS EXCLUSIVE MODE;

-- ID 단독 FK를 복합 PK로 자동 전환할 수 없다. 예상 밖 참조가 있으면 중단한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE contype='f' AND confrelid='user_behavior_events'::regclass) THEN
        RAISE EXCEPTION 'Unexpected incoming FK to user_behavior_events; review before partitioning';
    END IF;
END $$;

CREATE TABLE user_behavior_events_p (
    LIKE user_behavior_events INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING IDENTITY INCLUDING STORAGE,
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

-- 기존 첫 달부터 기존 마지막 달과 KST 이번 달+12개월 중 더 늦은 달까지 만든다.
-- received_at 값은 시간대 변환 없이 그대로 복사한다.
DO $$
DECLARE
    current_month date := date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')::date;
    m date;
    last_month date;
BEGIN
    SELECT least(coalesce(date_trunc('month', min(received_at))::date, current_month), current_month),
           greatest(coalesce(date_trunc('month', max(received_at))::date, current_month),
                    (current_month + interval '12 months')::date)
    INTO m, last_month FROM user_behavior_events;
    WHILE m <= last_month LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF user_behavior_events_p FOR VALUES FROM (%L) TO (%L)',
                       'user_behavior_events_' || to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;
CREATE TABLE user_behavior_events_default PARTITION OF user_behavior_events_p DEFAULT;

CREATE INDEX ube_p_user_opportunity ON user_behavior_events_p (user_id, opportunity_id);
CREATE INDEX ube_p_opportunity ON user_behavior_events_p (opportunity_id);

-- users ON DELETE CASCADE와 opportunities 삭제 제한을 포함해 기존 FK를 보존한다.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
             WHERE conrelid='user_behavior_events'::regclass AND contype='f' LOOP
        EXECUTE format('ALTER TABLE user_behavior_events_p ADD CONSTRAINT %I %s', c.conname, c.def);
    END LOOP;
END $$;

INSERT INTO user_behavior_events_p SELECT * FROM user_behavior_events;
DO $$
BEGIN
    IF (SELECT count(*) FROM user_behavior_events_p) <> (SELECT count(*) FROM user_behavior_events)
       OR EXISTS (SELECT 1 FROM user_behavior_events_default) THEN
        RAISE EXCEPTION 'Behavior event copy count mismatch or nonempty DEFAULT';
    END IF;
END $$;

DO $$
DECLARE old_pkey text; old_seq text; new_seq text;
BEGIN
    SELECT conindid::regclass::text INTO old_pkey FROM pg_constraint
    WHERE conrelid='user_behavior_events'::regclass AND contype='p';
    old_seq := pg_get_serial_sequence('user_behavior_events', 'id');
    new_seq := pg_get_serial_sequence('user_behavior_events_p', 'id');
    ALTER TABLE user_behavior_events RENAME TO user_behavior_events_old;
    EXECUTE format('ALTER INDEX %s RENAME TO user_behavior_events_old_pkey', old_pkey);
    ALTER INDEX idx_user_behavior_events_user_opportunity RENAME TO ube_old_user_opportunity;
    ALTER INDEX idx_user_behavior_events_opportunity RENAME TO ube_old_opportunity;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO user_behavior_events_old_id_seq', old_seq);
    ALTER TABLE user_behavior_events_p RENAME TO user_behavior_events;
    ALTER INDEX user_behavior_events_p_pkey RENAME TO user_behavior_events_pkey;
    ALTER INDEX ube_p_user_opportunity RENAME TO idx_user_behavior_events_user_opportunity;
    ALTER INDEX ube_p_opportunity RENAME TO idx_user_behavior_events_opportunity;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO user_behavior_events_id_seq', new_seq);
END $$;
SELECT setval(pg_get_serial_sequence('user_behavior_events', 'id'),
              coalesce((SELECT max(id) FROM user_behavior_events), 0) + 1, false);
ANALYZE user_behavior_events;
COMMIT;
-- 다음 날 동작·탈퇴 삭제 검증 후 합의한 뒤 _old를 별도 트랜잭션으로 삭제한다.
