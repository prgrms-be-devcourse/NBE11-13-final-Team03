-- raw_external_record 를 source_id LIST 파티션 테이블로 교체하고,
-- raw_normalization_request 의 FK 를 (raw_record_id, source_id) 복합 FK 로 바꾼다.
-- 실행 계정: application_user (테이블 소유자). 수집 스케줄·정제 처리기가 멈춘 시간대에, 새 data-service 버전 기동 전에 1회 실행한다.
-- 전제: raw_normalization_request 엔티티에 source_id 열이 추가된 버전(새 data-service)과 함께 배포한다.
-- D+1 수집·정제·롤백 가능 여부 확인 후에만 별도 트랜잭션으로 raw_external_record_old 를 삭제한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO application, pg_catalog;

-- 복사와 이름 교체 사이에 다른 작성자가 원본·요청·출처를 변경하지 못하게 한다.
LOCK TABLE external_source, raw_external_record, raw_normalization_request IN ACCESS EXCLUSIVE MODE;

-- 1. 부모 테이블. 열·NOT NULL·IDENTITY·TOAST 설정을 복사하고 PK 만 (id, source_id) 로 바꾼다.
CREATE TABLE raw_external_record_p (
    LIKE raw_external_record INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING IDENTITY INCLUDING STORAGE,
    PRIMARY KEY (id, source_id)
) PARTITION BY LIST (source_id);

-- 2. 출처별 파티션 (external_source 의 실제 id 로). 매일 UNCHANGED 행의 last_seen_at 갱신이 HOT 갱신이 되도록 fillfactor 70.
--    모르는 출처의 행이 실패하지 않도록 DEFAULT 파티션을 둔다 (정상 상태에서는 0행).
DO $$
DECLARE s record;
BEGIN
    FOR s IN SELECT id, lower(source_code) AS code FROM external_source ORDER BY id LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF raw_external_record_p FOR VALUES IN (%s) WITH (fillfactor = 70)',
                       'raw_external_record_' || s.code, s.id);
    END LOOP;
END $$;
CREATE TABLE raw_external_record_default PARTITION OF raw_external_record_p DEFAULT WITH (fillfactor = 70);

-- 3. 인덱스: 엔티티의 idx_raw_source_external_id 와 같은 열. 옛 테이블이 남아 있는 동안 임시 이름.
CREATE INDEX rer_p_source_external_id ON raw_external_record_p (source_id, external_id, id);

-- 4. 나가는 FK(source_id → external_source, sync_id → sync_history)를 옛 테이블에서 이름까지 복사
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname, pg_get_constraintdef(oid) AS def
             FROM pg_constraint WHERE conrelid = 'raw_external_record'::regclass AND contype = 'f' LOOP
        EXECUTE format('ALTER TABLE raw_external_record_p ADD CONSTRAINT %I %s', c.conname, c.def);
    END LOOP;
END $$;

-- 5. 복사
INSERT INTO raw_external_record_p SELECT * FROM raw_external_record;

-- 6. 건수 검증
DO $$
DECLARE old_count bigint; new_count bigint; default_count bigint;
BEGIN
    SELECT count(*) INTO old_count FROM raw_external_record;
    SELECT count(*) INTO new_count FROM raw_external_record_p;
    SELECT count(*) INTO default_count FROM raw_external_record_default;
    IF old_count <> new_count THEN
        RAISE EXCEPTION 'row count mismatch: old=% new=%', old_count, new_count;
    END IF;
    IF default_count <> 0 THEN
        RAISE EXCEPTION 'DEFAULT partition has % rows (source without partition)', default_count;
    END IF;
END $$;

-- 7. raw_normalization_request: source_id 열 추가·채움, 단일 열 FK 제거, 복합 FK 로 교체
ALTER TABLE raw_normalization_request ADD COLUMN IF NOT EXISTS source_id bigint;  -- 롤백 뒤 재적용 시 열이 남아 있을 수 있다
UPDATE raw_normalization_request q SET source_id = r.source_id
FROM raw_external_record r WHERE r.id = q.raw_record_id;
ALTER TABLE raw_normalization_request ALTER COLUMN source_id SET NOT NULL;

DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'raw_normalization_request'::regclass AND contype = 'f'
               AND confrelid = 'raw_external_record'::regclass LOOP
        EXECUTE format('ALTER TABLE raw_normalization_request DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE raw_normalization_request
    ADD CONSTRAINT fk_normalization_request_raw
        FOREIGN KEY (raw_record_id, source_id) REFERENCES raw_external_record_p (id, source_id),
    ADD CONSTRAINT fk_normalization_request_previous
        FOREIGN KEY (previous_raw_record_id, source_id) REFERENCES raw_external_record_p (id, source_id);

-- 8. 이름 교체. 옛 테이블의 PK 인덱스·IDENTITY 시퀀스 이름은 카탈로그에서 찾는다 (생성 시점에 따라 기본 이름과 다를 수 있음).
DO $$
DECLARE old_pkey text; old_seq text; new_seq text;
BEGIN
    SELECT conindid::regclass::text INTO old_pkey FROM pg_constraint
     WHERE conrelid = 'raw_external_record'::regclass AND contype = 'p';
    old_seq := pg_get_serial_sequence('raw_external_record', 'id');
    new_seq := pg_get_serial_sequence('raw_external_record_p', 'id');

    ALTER TABLE raw_external_record RENAME TO raw_external_record_old;
    EXECUTE format('ALTER INDEX %s RENAME TO raw_external_record_old_pkey', old_pkey);
    ALTER INDEX idx_raw_source_external_id RENAME TO rer_old_source_external_id;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO raw_external_record_old_id_seq', old_seq);

    ALTER TABLE raw_external_record_p RENAME TO raw_external_record;
    ALTER INDEX raw_external_record_p_pkey RENAME TO raw_external_record_pkey;
    ALTER INDEX rer_p_source_external_id RENAME TO idx_raw_source_external_id;
    EXECUTE format('ALTER SEQUENCE %s RENAME TO raw_external_record_id_seq', new_seq);
END $$;

-- 9. 새 IDENTITY 시퀀스를 옮긴 행의 최대값 뒤로
SELECT setval(pg_get_serial_sequence('raw_external_record', 'id'),
              COALESCE((SELECT max(id) FROM raw_external_record), 0) + 1, false);

ANALYZE raw_external_record;
ANALYZE raw_normalization_request;
COMMIT;
