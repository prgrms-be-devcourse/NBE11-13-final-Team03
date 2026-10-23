-- raw_normalization_request 테이블 생성 후 배포/초기화 계정으로 별도 실행한다.
-- 애플리케이션 시작 시 자동 실행하지 않는다. 오류 시 전체 설치를 롤백한다.
BEGIN;

CREATE OR REPLACE FUNCTION notify_raw_normalization_request() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('raw_normalization_requested', NEW.id::text);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE TRIGGER raw_normalization_request_notify
AFTER INSERT ON raw_normalization_request
FOR EACH ROW EXECUTE FUNCTION notify_raw_normalization_request();

COMMIT;
