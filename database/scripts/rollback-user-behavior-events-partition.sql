-- user-behavior-events-partition.sql 적용을 되돌린다. user_behavior_events_old 가 아직 남아 있을 때만 쓸 수 있다.
-- 실행 계정: application_user. backend 를 멈춘 뒤 실행한다. 엔티티 변경이 없었으므로 애플리케이션 롤백은 필요 없다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO application, pg_catalog;
LOCK TABLE user_behavior_events, user_behavior_events_old IN ACCESS EXCLUSIVE MODE;

-- 1. 전환 뒤 들어온 행을 옛 테이블로 복사 (행동 로그는 추가만 되므로 id 기준)
INSERT INTO user_behavior_events_old
SELECT * FROM user_behavior_events
WHERE id > (SELECT COALESCE(max(id), 0) FROM user_behavior_events_old);

-- 2. 건수 검증
DO $$
DECLARE old_count bigint; new_count bigint;
BEGIN
    SELECT count(*) INTO old_count FROM user_behavior_events_old;
    SELECT count(*) INTO new_count FROM user_behavior_events;
    IF old_count <> new_count THEN
        RAISE EXCEPTION 'row count mismatch after copy-back: old=% new=%', old_count, new_count;
    END IF;
END $$;

-- 3. 이름 되돌리기
DO $$
DECLARE p_pkey text; p_seq text; old_pkey text; old_seq text;
BEGIN
    SELECT conindid::regclass::text INTO p_pkey FROM pg_constraint
     WHERE conrelid = 'user_behavior_events'::regclass AND contype = 'p';
    p_seq := pg_get_serial_sequence('user_behavior_events', 'id');
    SELECT conindid::regclass::text INTO old_pkey FROM pg_constraint
     WHERE conrelid = 'user_behavior_events_old'::regclass AND contype = 'p';
    old_seq := pg_get_serial_sequence('user_behavior_events_old', 'id');

    ALTER TABLE user_behavior_events RENAME TO user_behavior_events_p;
    EXECUTE format('ALTER INDEX %s RENAME TO user_behavior_events_p_pkey', p_pkey);
    ALTER INDEX idx_user_behavior_events_user_opportunity RENAME TO ube_p_user_opportunity;
    ALTER INDEX idx_user_behavior_events_opportunity RENAME TO ube_p_opportunity;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO user_behavior_events_p_id_seq', p_seq);

    ALTER TABLE user_behavior_events_old RENAME TO user_behavior_events;
    EXECUTE format('ALTER INDEX %s RENAME TO user_behavior_events_pkey', old_pkey);
    ALTER INDEX ube_old_user_opportunity RENAME TO idx_user_behavior_events_user_opportunity;
    ALTER INDEX ube_old_opportunity RENAME TO idx_user_behavior_events_opportunity;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO user_behavior_events_id_seq', old_seq);
END $$;

-- 4. 옛 시퀀스를 복사해 온 행의 최대값 뒤로
SELECT setval(pg_get_serial_sequence('user_behavior_events', 'id'),
              COALESCE((SELECT max(id) FROM user_behavior_events), 0) + 1, false);

ANALYZE user_behavior_events;
COMMIT;
-- backend 가 정상이면 별도 트랜잭션: DROP TABLE user_behavior_events_p;
