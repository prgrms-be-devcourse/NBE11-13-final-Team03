-- raw-external-record-partition.sql 적용을 되돌린다. raw_external_record_old 가 아직 남아 있을 때만 쓸 수 있다.
-- 실행 계정: application_user. data-service 를 멈춘 뒤 실행하고, 끝나면 옛 버전(source_id 없는 엔티티) data-service 를 배포한다.
-- 전환 뒤 들어온 원본·갱신된 last_seen_at 은 옛 테이블로 복사하므로 데이터 손실은 없다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO application, pg_catalog;

LOCK TABLE raw_external_record, raw_external_record_old, raw_normalization_request IN ACCESS EXCLUSIVE MODE;

-- 1. 전환 뒤 새로 들어온 원본을 옛 테이블로 복사하고, 갱신된 last_seen_at 을 반영한다
INSERT INTO raw_external_record_old
SELECT * FROM raw_external_record
WHERE id > (SELECT COALESCE(max(id), 0) FROM raw_external_record_old);
UPDATE raw_external_record_old o SET last_seen_at = n.last_seen_at
FROM raw_external_record n WHERE n.id = o.id AND n.last_seen_at <> o.last_seen_at;

-- 2. 건수 검증
DO $$
DECLARE old_count bigint; new_count bigint;
BEGIN
    SELECT count(*) INTO old_count FROM raw_external_record_old;
    SELECT count(*) INTO new_count FROM raw_external_record;
    IF old_count <> new_count THEN
        RAISE EXCEPTION 'row count mismatch after copy-back: old=% new=%', old_count, new_count;
    END IF;
END $$;

-- 3. 요청 테이블 FK 를 옛 테이블(단일 열)로 되돌린다. source_id 열은 남기되 옛 엔티티가 모르는 열이므로 NOT NULL 을 푼다
ALTER TABLE raw_normalization_request
    DROP CONSTRAINT fk_normalization_request_raw,
    DROP CONSTRAINT fk_normalization_request_previous;
ALTER TABLE raw_normalization_request
    ADD CONSTRAINT fk_normalization_request_raw
        FOREIGN KEY (raw_record_id) REFERENCES raw_external_record_old (id),
    ADD CONSTRAINT fk_normalization_request_previous
        FOREIGN KEY (previous_raw_record_id) REFERENCES raw_external_record_old (id);
ALTER TABLE raw_normalization_request ALTER COLUMN source_id DROP NOT NULL;

-- 4. 이름 되돌리기 (파티션 테이블 → _p, 옛 테이블 → 원래 이름)
DO $$
DECLARE p_pkey text; p_seq text; old_pkey text; old_seq text;
BEGIN
    SELECT conindid::regclass::text INTO p_pkey FROM pg_constraint
     WHERE conrelid = 'raw_external_record'::regclass AND contype = 'p';
    p_seq := pg_get_serial_sequence('raw_external_record', 'id');
    SELECT conindid::regclass::text INTO old_pkey FROM pg_constraint
     WHERE conrelid = 'raw_external_record_old'::regclass AND contype = 'p';
    old_seq := pg_get_serial_sequence('raw_external_record_old', 'id');

    ALTER TABLE raw_external_record RENAME TO raw_external_record_p;
    EXECUTE format('ALTER INDEX %s RENAME TO raw_external_record_p_pkey', p_pkey);
    ALTER INDEX idx_raw_source_external_id RENAME TO rer_p_source_external_id;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO raw_external_record_p_id_seq', p_seq);

    ALTER TABLE raw_external_record_old RENAME TO raw_external_record;
    EXECUTE format('ALTER INDEX %s RENAME TO raw_external_record_pkey', old_pkey);
    ALTER INDEX rer_old_source_external_id RENAME TO idx_raw_source_external_id;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO raw_external_record_id_seq', old_seq);
END $$;

-- 5. 옛 시퀀스를 복사해 온 행의 최대값 뒤로
SELECT setval(pg_get_serial_sequence('raw_external_record', 'id'),
              COALESCE((SELECT max(id) FROM raw_external_record), 0) + 1, false);

ANALYZE raw_external_record;
COMMIT;
-- 옛 버전 data-service 가 정상이면 별도 트랜잭션: DROP TABLE raw_external_record_p;
