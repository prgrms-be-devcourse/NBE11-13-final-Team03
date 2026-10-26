package com.team3.notification.admin;

import com.team3.notification.message.NotificationMessageFingerprint;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A committed receipt means Slack accepted the message; no user Inbox/Delivery table is involved. */
public class AdminNotificationHandler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NotificationMessageFingerprint fingerprints;
    private final SlackWebhookSender slack;
    private final Clock clock;

    public AdminNotificationHandler(JdbcTemplate jdbc, ObjectMapper mapper, NotificationMessageFingerprint fingerprints,
                                    SlackWebhookSender slack, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.fingerprints = fingerprints; this.slack = slack; this.clock = clock;
    }

    public record Result(UUID messageId, boolean duplicate) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result handle(String body) {
        var message = CollectionCompletedMessage.decode(mapper, body);
        var hash = fingerprints.fingerprint(message);
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        // A concurrent duplicate waits for this transaction, then sees the committed receipt or retries its insert.
        int inserted = jdbc.update("""
                INSERT INTO admin_consumed_messages(message_id, event_type, request_fingerprint, consumed_at)
                VALUES (?, 'COLLECTION_COMPLETED', ?, ?) ON CONFLICT (message_id) DO NOTHING
                """, message.messageId(), hash, Timestamp.from(clock.instant()));
        if (inserted == 0) {
            var existing = jdbc.queryForObject("SELECT request_fingerprint FROM admin_consumed_messages WHERE message_id = ?",
                    String.class, message.messageId());
            if (!hash.equals(existing)) throw new IllegalArgumentException("ADMIN_MESSAGE_IDENTITY_CONFLICT");
            return new Result(message.messageId(), true);
        }
        // Bounded HTTP call while the receipt is uncommitted: failed Slack sends leave no durable receipt.
        // Slack success followed by DB commit failure can send twice; Incoming Webhooks have no idempotency key.
        slack.send(message);
        return new Result(message.messageId(), false);
    }
}
