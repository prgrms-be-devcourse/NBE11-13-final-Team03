#!/bin/sh
# Local docker compose only: applies Notification manual DDL as its schema owner.
# Notification runs with ddl-auto=validate, so its tables must exist before it starts.
# Application tables are created by backend (ddl-auto=update) on first boot.
set -eu

for script in notification-inbox.sql notification-user-api.sql notification-withdrawal.sql notification-delivery.sql notification-admin.sql; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -c 'SET ROLE notification_user' -f "/yon-scripts/$script"
done
