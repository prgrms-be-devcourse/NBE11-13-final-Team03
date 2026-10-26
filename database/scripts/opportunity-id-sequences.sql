-- 정규화 엔티티 ID를 IDENTITY → 시퀀스(allocationSize 50)로 바꾼 버전의 data-service를 데이터가 있는 DB에 처음 배포하기 **전에** 한 번 실행한다.
-- 새 DB(공고 테이블이 없음)는 data-service가 처음 뜰 때 시퀀스를 만들므로 실행하지 않는다.
-- 이유: 시퀀스가 없으면 ddl-auto update가 *_seq를 1부터 새로 만들어 기존 ID와 겹친다. 여기서 Hibernate와 같은 모양(INCREMENT BY 50)으로
--       미리 만들고 최대 ID 뒤로 옮겨 두면 update는 그대로 쓴다. 반복 실행해도 된다 (다시 최대 ID 뒤로 맞춤).
-- 실행 (DB 서버 ~/yon-db, 테이블 소유자 application_user로):
--   { echo 'SET ROLE application_user;'; cat sql/opportunity-id-sequences.sql; } | docker compose exec -T postgres-db psql -U postgres -d yon -v ON_ERROR_STOP=1
BEGIN;
SET LOCAL search_path TO application, pg_catalog;
SET LOCAL lock_timeout = '5s';
CREATE SEQUENCE IF NOT EXISTS opportunities_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS opportunity_versions_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS opportunity_conditions_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS opportunity_regions_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS application_windows_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS opportunity_sources_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS training_course_details_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS youth_policy_details_seq START WITH 1 INCREMENT BY 50;
SELECT setval('opportunities_seq', (SELECT coalesce(max(id), 0) FROM opportunities) + 50, false);
SELECT setval('opportunity_versions_seq', (SELECT coalesce(max(id), 0) FROM opportunity_versions) + 50, false);
SELECT setval('opportunity_conditions_seq', (SELECT coalesce(max(id), 0) FROM opportunity_conditions) + 50, false);
SELECT setval('opportunity_regions_seq', (SELECT coalesce(max(id), 0) FROM opportunity_regions) + 50, false);
SELECT setval('application_windows_seq', (SELECT coalesce(max(id), 0) FROM application_windows) + 50, false);
SELECT setval('opportunity_sources_seq', (SELECT coalesce(max(id), 0) FROM opportunity_sources) + 50, false);
SELECT setval('training_course_details_seq', (SELECT coalesce(max(id), 0) FROM training_course_details) + 50, false);
SELECT setval('youth_policy_details_seq', (SELECT coalesce(max(id), 0) FROM youth_policy_details) + 50, false);
COMMIT;
