package com.team3.notification.service;

import com.team3.notification.domain.entity.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;

/** Called inside the already user-locked receive transaction; no external I/O. */
@Component
public class NotificationDeliveryQueue {
    private final JdbcTemplate jdbc;
    public NotificationDeliveryQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean hasActiveEndpoint(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_endpoints WHERE user_id = ? AND active)", Boolean.class, userId));
    }

    public int enqueue(Notification notification, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Delivery creation requires the receive transaction");
        }
        var timestamp = Timestamp.from(now);
        return jdbc.update("""
                INSERT INTO notification_deliveries
                  (notification_id, endpoint_id, user_id, status, retry_count, next_retry_at, created_at)
                SELECT ?, e.id, e.user_id, 'PENDING', 0, ?, ? FROM notification_endpoints e
                WHERE e.user_id = ? AND e.active
                ON CONFLICT (notification_id, endpoint_id) DO NOTHING
                """, notification.getId(), timestamp, timestamp, notification.getUserId());
    }
}
