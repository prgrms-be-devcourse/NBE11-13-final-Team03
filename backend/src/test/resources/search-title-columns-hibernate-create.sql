-- ddl-auto:create를 쓰는 PostgreSQL 테스트 전용 import 파일 (hibernate.hbm2ddl.import_files).
-- Hibernate는 엔티티 매핑대로 search_title_* 를 일반 컬럼으로 만든다. 일회용 컨테이너의 빈 스키마에서만,
-- 그 직후 운영 스크립트(database/scripts/search-title-columns.sql)와 같은 정의의 생성 컬럼으로 바꾼다.
-- 운영·공유 DB에는 쓰지 않는다. 운영 스크립트는 일반 컬럼을 발견하면 지우지 않고 멈춘다.
-- Hibernate import 파일은 한 줄을 한 문장으로 읽는다.
ALTER TABLE opportunities DROP COLUMN search_title_norm, DROP COLUMN search_title_strip;
ALTER TABLE opportunities ADD COLUMN search_title_norm text GENERATED ALWAYS AS (lower(normalize(title, NFKC))) STORED, ADD COLUMN search_title_strip text GENERATED ALWAYS AS (regexp_replace(lower(normalize(title, NFKC)), '[\s·・‧ᆞ\-]', '', 'g')) STORED;
