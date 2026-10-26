package com.team3.notification.service;

import com.team3.notification.config.NotificationPushProperties;
import com.team3.notification.config.NotificationSlotProperties;
import com.team3.notification.push.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationDeliveryStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NotificationUserGuard guard;
    private final NotificationPushProperties properties;
    private final NotificationSlotProperties slots;
    public NotificationDeliveryStore(JdbcTemplate jdbc, Clock clock, NotificationUserGuard guard, NotificationPushProperties properties,
            NotificationSlotProperties slots) {
        this.jdbc = jdbc; this.clock = clock; this.guard = guard; this.properties = properties; this.slots = slots;
    }
    public record Claim(long id, long userId, UUID leaseToken) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNext() {
        if (slots.enabled()) return Optional.empty();
        var token = UUID.randomUUID(); var now = Timestamp.from(clock.instant());
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM notification_deliveries WHERE status = 'PENDING' AND next_retry_at <= ?
                  ORDER BY next_retry_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE notification_deliveries d SET status = 'SENDING', processing_started_at = ?, lease_token = ?
                FROM candidate c WHERE d.id = c.id RETURNING d.id, d.user_id
                """, (rs, row) -> new Claim(rs.getLong(1), rs.getLong(2), token), now, now, token).stream().findFirst();
    }

    /** User lock precedes Delivery row lock, same as withdrawal and setting/Endpoint mutations. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PushMessage> prepare(Claim claim) {
        if (slots.enabled()) return Optional.empty();
        guard.lock(claim.userId());
        var rows = jdbc.query("""
                SELECT d.notification_id, d.endpoint_id, e.fcm_token, e.active, n.title, n.content, n.link,
                  LEAST(n.expires_at, COALESCE(n.push_expires_at, n.expires_at)) AS expires_at, n.message_id, n.correlation_id,
                  COALESCE((SELECT s.push_enabled FROM notification_settings s WHERE s.user_id = d.user_id), false) AS consent
                FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id
                JOIN notification_endpoints e ON e.id = d.endpoint_id AND e.user_id = d.user_id
                WHERE d.id = ? AND d.user_id = ? AND d.status = 'SENDING' AND d.lease_token = ? FOR UPDATE OF d
                """, (rs, row) -> new Prepared(new PushMessage(claim.id(), rs.getLong("notification_id"), claim.userId(),
                        rs.getLong("endpoint_id"), claim.leaseToken(), rs.getString("fcm_token"), rs.getString("title"),
                        rs.getString("content"), rs.getString("link"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("message_id", UUID.class), rs.getObject("correlation_id", UUID.class)),
                        rs.getBoolean("active"), rs.getBoolean("consent")), claim.id(), claim.userId(), claim.leaseToken());
        if (rows.isEmpty()) return Optional.empty();
        var prepared = rows.getFirst();
        String reason = guard.isWithdrawn(claim.userId()) ? "USER_WITHDRAWN"
                : !clock.instant().isBefore(prepared.message().expiresAt()) ? "PUSH_EXPIRED"
                : !prepared.consent() ? "PUSH_CONSENT_REVOKED" : !prepared.active() ? "ENDPOINT_INACTIVE" : null;
        if (reason != null) { skip(claim, reason); return Optional.empty(); }
        return Optional.of(prepared.message());
    }
    private record Prepared(PushMessage message, boolean active, boolean consent) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sent(Claim claim, String receipt) {
        return jdbc.update("""
                UPDATE notification_deliveries SET status = 'SENT', sent_at = ?, fcm_message_id = ?,
                  processing_started_at = NULL, lease_token = NULL, last_error = NULL
                WHERE id = ? AND status = 'SENDING' AND lease_token = ?
                """, Timestamp.from(clock.instant()), receipt, claim.id(), claim.leaseToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(Claim claim, PushMessage message, PushFailure failure) {
        guard.lock(claim.userId());
        if (failure.kind() == PushFailure.Kind.INVALID_TOKEN) {
            if (!skip(claim, "FCM_UNREGISTERED")) return false;
            jdbc.update("UPDATE notification_endpoints SET active = false, updated_at = ? WHERE id = ? AND user_id = ? AND fcm_token = ?",
                    Timestamp.from(clock.instant()), message.endpointId(), claim.userId(), message.fcmToken());
            return true;
        }
        if ("PUSH_EXPIRED".equals(failure.getMessage())) return skip(claim, "PUSH_EXPIRED");
        boolean configuration = failure.kind() == PushFailure.Kind.CONFIGURATION;
        return jdbc.update("""
                UPDATE notification_deliveries SET retry_count = retry_count + ?,
                  status = CASE WHEN ? OR (? AND retry_count + 1 < ?) THEN 'PENDING' ELSE 'FAILED' END,
                  next_retry_at = ?::timestamptz + (? * power(2, least(retry_count, 6))) * interval '1 millisecond',
                  processing_started_at = NULL, lease_token = NULL, last_error = ?
                WHERE id = ? AND status = 'SENDING' AND lease_token = ?
                """, configuration ? 0 : 1, configuration, failure.kind() == PushFailure.Kind.TRANSIENT, properties.maxAttempts(),
                Timestamp.from(clock.instant()), properties.retryDelay().toMillis(), failure.getMessage(), claim.id(), claim.leaseToken()) == 1;
    }

    private boolean skip(Claim claim, String reason) {
        return jdbc.update("""
                UPDATE notification_deliveries SET status = 'SKIPPED', last_error = ?, processing_started_at = NULL, lease_token = NULL
                WHERE id = ? AND status = 'SENDING' AND lease_token = ?
                """, reason, claim.id(), claim.leaseToken()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStale() {
        if (slots.enabled()) return 0;
        return jdbc.update("""
                WITH stale AS (
                  SELECT id FROM notification_deliveries WHERE status = 'SENDING' AND processing_started_at < ?
                  ORDER BY processing_started_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE notification_deliveries d SET retry_count = d.retry_count + 1,
                  status = CASE WHEN d.retry_count + 1 < ? THEN 'PENDING' ELSE 'FAILED' END,
                  next_retry_at = ?::timestamptz + (? * power(2, least(d.retry_count, 6))) * interval '1 millisecond',
                  processing_started_at = NULL, lease_token = NULL, last_error = 'DELIVERY_LEASE_EXPIRED'
                FROM stale s WHERE d.id = s.id
                """, Timestamp.from(clock.instant().minus(properties.leaseTimeout())), properties.batchSize(), properties.maxAttempts(),
                Timestamp.from(clock.instant()), properties.retryDelay().toMillis());
    }
}
