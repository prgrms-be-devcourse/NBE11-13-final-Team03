-- 정규화 엔티티 ID를 시퀀스로 바꾼 data-service에서 IDENTITY 버전(이전 버전)으로 되돌릴 때, 이전 버전을 띄우기 **전에** 한 번 실행한다.
-- 이유: 시퀀스 버전이 넣은 행은 IDENTITY 내부 시퀀스(opportunities_id_seq 등)를 거치지 않아, 그대로 되돌리면 첫 INSERT부터 PK가 충돌한다.
--       각 IDENTITY 시퀀스를 최대 ID 뒤로 맞춘다. 반복 실행해도 된다. *_seq(시퀀스 버전용)는 지우지 않는다 (다시 올릴 때 opportunity-id-sequences.sql로 맞춤).
-- 실행 (DB 서버 ~/yon-db, 테이블 소유자 application_user로):
--   { echo 'SET ROLE application_user;'; cat sql/opportunity-id-identity-rollback.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
BEGIN;
SET LOCAL search_path TO application, pg_catalog;
SET LOCAL lock_timeout = '5s';
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['opportunities', 'opportunity_versions', 'opportunity_conditions', 'opportunity_regions',
                             'application_windows', 'opportunity_sources', 'training_course_details', 'youth_policy_details'] LOOP
        EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), (SELECT coalesce(max(id), 0) + 1 FROM %I), false)',
                       'application.' || t, t);
    END LOOP;
END $$;
COMMIT;
