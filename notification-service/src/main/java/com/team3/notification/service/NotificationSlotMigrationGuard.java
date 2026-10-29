package com.team3.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail closed instead of converting an old per-notification delivery into a second slot delivery. */
@Component
@ConditionalOnProperty(name="yon.notification.push.slots.enabled",havingValue="true")
public class NotificationSlotMigrationGuard {
    private final JdbcTemplate jdbc;
    public NotificationSlotMigrationGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void check() {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_deliveries WHERE status IN ('PENDING','SENDING'))",Boolean.class))) {
            throw new IllegalStateException("Legacy active Delivery exists; stop senders and resolve migration before enabling slots");
        }
        // Verify operator DDL even when push.enabled is false and no scheduled worker will access it.
        jdbc.queryForObject("SELECT count(*) FROM notification_push_pending WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM notification_push_bundles WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM notification_push_slot_usage WHERE false",Long.class);
        jdbc.queryForObject("SELECT count(*) FROM notification_bundle_deliveries WHERE false",Long.class);
    }
}
