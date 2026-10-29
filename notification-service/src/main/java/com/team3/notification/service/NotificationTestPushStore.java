package com.team3.notification.service;

import com.team3.notification.push.PushMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Service
public class NotificationTestPushStore {
    private final JdbcTemplate jdbc;
    private final NotificationUserGuard guard;
    private final Clock clock;

    public NotificationTestPushStore(JdbcTemplate jdbc, NotificationUserGuard guard, Clock clock) {
        this.jdbc = jdbc; this.guard = guard; this.clock = clock;
    }

    /** Same user lock/preflight rules as normal delivery; no database transaction spans the FCM call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PushMessage prepare(long userId, long notificationId, long endpointId, UUID requestId) {
        guard.lockActive(userId);
        var rows = jdbc.query("""
                SELECT n.title, n.content, n.link, n.message_id, n.correlation_id,
                  LEAST(n.expires_at, COALESCE(n.push_expires_at, n.expires_at)) AS expires_at,
                  e.fcm_token, e.active,
                  COALESCE((SELECT s.push_enabled FROM notification_settings s WHERE s.user_id = n.user_id), false) AS consent
                FROM notifications n JOIN notification_endpoints e ON e.user_id = n.user_id
                WHERE n.id = ? AND n.user_id = ? AND e.id = ?
                """, (rs, row) -> new Prepared(new PushMessage(0, notificationId, userId, endpointId, requestId,
                        rs.getString("fcm_token"), rs.getString("title"), rs.getString("content"),
                        rs.getString("link"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("message_id", UUID.class), rs.getObject("correlation_id", UUID.class)),
                        rs.getBoolean("active"), rs.getBoolean("consent")), notificationId, userId, endpointId);
        if (rows.isEmpty()) throw new NotificationUserApiException(404, "TEST_PUSH_TARGET_NOT_FOUND");
        var target = rows.getFirst();
        if (!target.message().title().startsWith("[시연]") && !target.message().title().startsWith("[테스트]")) {
            throw new NotificationUserApiException(409, "TEST_PUSH_REQUIRES_TEST_NOTIFICATION");
        }
        if (!target.consent()) throw new NotificationUserApiException(409, "PUSH_CONSENT_REVOKED");
        if (!target.active()) throw new NotificationUserApiException(409, "ENDPOINT_INACTIVE");
        if (!clock.instant().isBefore(target.message().expiresAt())) {
            throw new NotificationUserApiException(409, "PUSH_EXPIRED");
        }
        return target.message();
    }

    private record Prepared(PushMessage message, boolean active, boolean consent) { }

    /** Do not deactivate a replacement token registered while the FCM request was in flight. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deactivateInvalidToken(PushMessage message) {
        guard.lock(message.userId());
        jdbc.update("""
                UPDATE notification_endpoints SET active = false, updated_at = ?
                WHERE id = ? AND user_id = ? AND fcm_token = ? AND active = true
                """, Timestamp.from(clock.instant()), message.endpointId(), message.userId(), message.fcmToken());
    }
}
