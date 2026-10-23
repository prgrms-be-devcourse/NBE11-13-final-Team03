-- Run as a database/role administrator against the dedicated Y database, never at app startup.
-- No passwords are stored here. Set them separately with psql \password after provisioning.
-- Transactional and repeatable; abort if existing roles have elevated privileges/membership.
BEGIN;

DO $$
DECLARE
    role_name text;
BEGIN
    FOREACH role_name IN ARRAY ARRAY['application_user', 'notification_user'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', role_name);
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name
                   AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
           OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = role_name)) THEN
            RAISE EXCEPTION 'Role % must be unprivileged and have no inherited memberships', role_name;
        END IF;
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), role_name);
    END LOOP;
END $$;

CREATE SCHEMA IF NOT EXISTS application AUTHORIZATION application_user;
CREATE SCHEMA IF NOT EXISTS notification AUTHORIZATION notification_user;

DO $$
DECLARE
    boundary text;
    owner_name text;
BEGIN
    FOREACH boundary IN ARRAY ARRAY['application', 'notification'] LOOP
        owner_name := boundary || '_user';
        IF (SELECT nspowner FROM pg_namespace WHERE nspname = boundary)
                <> (SELECT oid FROM pg_roles WHERE rolname = owner_name) THEN
            RAISE EXCEPTION 'Schema % has an unexpected owner; review before provisioning', boundary;
        END IF;
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC, application_user, notification_user', boundary);
        EXECUTE format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', boundary, owner_name);
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC, %I', boundary,
                       CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM PUBLIC, %I', boundary,
                       CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %I FROM PUBLIC, %I', boundary,
                       CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        -- Per-schema REVOKE cannot remove globally granted defaults (notably function EXECUTE).
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON FUNCTIONS FROM PUBLIC, %I',
                       owner_name, CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON FUNCTIONS FROM PUBLIC, %I',
                       owner_name, boundary, CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON TABLES FROM PUBLIC, %I',
                       owner_name, CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC, %I',
                       owner_name, boundary, CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE ALL ON SEQUENCES FROM PUBLIC, %I',
                       owner_name, CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC, %I',
                       owner_name, boundary, CASE boundary WHEN 'application' THEN 'notification_user' ELSE 'application_user' END);
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path TO %I, pg_catalog', owner_name, current_database(), boundary);
    END LOOP;
    EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC, application_user, notification_user', current_database());
END $$;

-- PUBLIC grants apply to every login, so revoking just the two accounts would not isolate public.
-- This affects other users of this database. Only use in the dedicated Y database.
REVOKE ALL ON SCHEMA public FROM PUBLIC, application_user, notification_user;

COMMIT;
