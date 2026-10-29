#!/bin/sh
# Local docker-compose only: sets login passwords after database-boundaries.sql.
# Runs once on an empty data volume. Never use in production.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v application_password="$APPLICATION_DB_PASSWORD" \
  -v notification_password="$NOTIFICATION_DB_PASSWORD" <<'SQL'
ALTER ROLE application_user PASSWORD :'application_password';
ALTER ROLE notification_user PASSWORD :'notification_password';
SQL
