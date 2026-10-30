-- Disposable Testcontainers database only. Native boundary DDL runs before this file.
ALTER ROLE application_user PASSWORD 'replica-test-application-only';
CREATE ROLE replica_test WITH LOGIN REPLICATION PASSWORD 'replica-test-stream-only';
