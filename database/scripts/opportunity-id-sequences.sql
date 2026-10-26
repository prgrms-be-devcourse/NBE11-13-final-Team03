-- 정규화 엔티티 ID를 IDENTITY → 시퀀스(allocationSize 50)로 바꾼 뒤, 이미 데이터가 있는 DB에서 한 번 실행한다.
-- 새 DB는 data-service가 처음 뜰 때 시퀀스를 만들고 비어 있으므로 실행할 필요 없다.
-- 기존 DB: ddl-auto update가 *_seq 시퀀스를 1부터 새로 만들므로, 기존 ID와 겹치지 않게 최대 ID 뒤로 옮긴다.
-- 실행: data-service를 새 버전으로 한 번 띄워 시퀀스가 생긴 뒤, 적재가 돌기 전에 application_user(테이블 소유자)로 실행. 반복 실행해도 된다.
--   { echo 'SET ROLE application_user;'; cat opportunity-id-sequences.sql; } | psql -U postgres -d yon -v ON_ERROR_STOP=1
BEGIN;
SELECT setval('opportunities_seq',            (SELECT coalesce(max(id), 0) FROM opportunities) + 50, false);
SELECT setval('opportunity_versions_seq',     (SELECT coalesce(max(id), 0) FROM opportunity_versions) + 50, false);
SELECT setval('opportunity_conditions_seq',   (SELECT coalesce(max(id), 0) FROM opportunity_conditions) + 50, false);
SELECT setval('opportunity_regions_seq',      (SELECT coalesce(max(id), 0) FROM opportunity_regions) + 50, false);
SELECT setval('application_windows_seq',      (SELECT coalesce(max(id), 0) FROM application_windows) + 50, false);
SELECT setval('opportunity_sources_seq',      (SELECT coalesce(max(id), 0) FROM opportunity_sources) + 50, false);
SELECT setval('training_course_details_seq',  (SELECT coalesce(max(id), 0) FROM training_course_details) + 50, false);
SELECT setval('youth_policy_details_seq',     (SELECT coalesce(max(id), 0) FROM youth_policy_details) + 50, false);
COMMIT;
