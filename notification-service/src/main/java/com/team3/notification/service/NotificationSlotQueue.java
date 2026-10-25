package com.team3.notification.service;

import com.team3.notification.domain.entity.Notification;
import com.team3.notification.message.NotificationType;
import com.team3.notification.push.PushSlotPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class NotificationSlotQueue {
    private final JdbcTemplate jdbc;
    public NotificationSlotQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int enqueue(Notification notification, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Receive transaction required");
        var threshold = notification.getPayload().get("threshold");
        var priority = PushSlotPolicy.priority(notification.getType(), threshold instanceof String text ? text : null);
        String reason = priority.isEmpty() ? "UNSUPPORTED_PUSH_TYPE"
                : notification.getType() == NotificationType.DEADLINE_SOON && notification.getPushExpiresAt() == null
                ? "LEGACY_DEADLINE_PUSH_EXPIRY_MISSING" : null;
        return jdbc.update("""
                INSERT INTO notification_push_pending(notification_id,user_id,priority,status,reason,created_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT (notification_id) DO NOTHING
                """, notification.getId(), notification.getUserId(), priority.isPresent() ? priority.getAsInt() : null,
                reason == null ? "PENDING" : "SKIPPED", reason, Timestamp.from(now));
    }
}
