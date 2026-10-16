-- 기존 원본 저장 테이블이 있는 PostgreSQL DB에 수동 최신화 기능을 추가한다.
-- 애플리케이션 시작 전에 적용한다. 새 DB는 기존 테이블 생성이 먼저 필요하다.
BEGIN;

ALTER TABLE sync_history
    ADD COLUMN IF NOT EXISTS new_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS unchanged_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS page_failures jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS employment24_page_failures jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS incomplete_ncs_codes jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS record_failures jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_sync_source_status ON sync_history (source_id, status);

COMMIT;
