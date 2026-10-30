-- Dedicated Testcontainers database only.
ALTER ROLE notification_user PASSWORD 'test-only-notification-password';
ALTER ROLE application_user PASSWORD 'test-only-notification-password';
