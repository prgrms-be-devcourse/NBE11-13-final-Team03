package com.team3.notification.service;

import com.team3.notification.domain.entity.*;
import com.team3.notification.domain.repository.*;
import com.team3.notification.message.UserWithdrawnMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service
public class NotificationWithdrawalTransaction {
    private final NotificationUserGuard guard;
    private final WithdrawnUserRepository withdrawn;
    private final ConsumedMessageRepository consumed;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationWithdrawalTransaction(NotificationUserGuard guard, WithdrawnUserRepository withdrawn,
            ConsumedMessageRepository consumed, JdbcTemplate jdbc, Clock clock) {
        this.guard = guard; this.withdrawn = withdrawn; this.consumed = consumed; this.jdbc = jdbc; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationReceptionResult receive(UserWithdrawnMessage message, String fingerprint) {
        guard.lock(message.userId());
        var previous = consumed.findByMessageId(message.messageId());
        if (previous.isPresent()) {
            var existing = previous.orElseThrow();
            if (!existing.getRequestFingerprint().equals(fingerprint)) throw new MessageIdentityConflictException();
            return new NotificationReceptionResult(message.messageId(), existing.getResult(), null, true);
        }
        var now = clock.instant();
        if (!guard.isWithdrawn(message.userId())) {
            withdrawn.saveAndFlush(new WithdrawnUser(message.userId(), message.occurredAt(), now));
        }
        // No Application DB access. Keep consumption receipts for replay detection; their FK is ON DELETE SET NULL.
        jdbc.update("""
                UPDATE notification_deliveries SET status = 'SKIPPED', last_error = 'USER_WITHDRAWN',
                    processing_started_at = NULL, lease_token = NULL
                WHERE user_id = ? AND status IN ('PENDING', 'SENDING')
                """, message.userId());
        // Delivery rows are then removed by the internal ON DELETE CASCADE FKs.
        jdbc.update("DELETE FROM notification_endpoints WHERE user_id = ?", message.userId());
        jdbc.update("DELETE FROM notification_settings WHERE user_id = ?", message.userId());
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", message.userId());
        consumed.saveAndFlush(ConsumedMessage.withdrawal(message, fingerprint, now));
        return new NotificationReceptionResult(message.messageId(), ReceptionStatus.WITHDRAWN, null, false);
    }
}
