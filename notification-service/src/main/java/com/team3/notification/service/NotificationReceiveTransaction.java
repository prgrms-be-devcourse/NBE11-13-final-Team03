package com.team3.notification.service;

import com.team3.notification.domain.entity.*;
import com.team3.notification.domain.repository.*;
import com.team3.notification.message.NotificationChannel;
import com.team3.notification.message.NotificationRequestedMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import static com.team3.notification.domain.entity.ReceptionStatus.*;

@Service
public class NotificationReceiveTransaction {
    private final NotificationRepository notifications;
    private final ConsumedMessageRepository consumedMessages;
    private final Clock clock;

    public NotificationReceiveTransaction(NotificationRepository notifications,
                                           ConsumedMessageRepository consumedMessages, Clock clock) {
        this.notifications = notifications;
        this.consumedMessages = consumedMessages;
        this.clock = clock;
    }

    /** Separate bean/transaction so a uniqueness conflict is fully rolled back before duplicate lookup. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationReceptionResult receive(NotificationRequestedMessage message, String fingerprint) {
        var consumed = consumedMessages.findByMessageId(message.messageId());
        if (consumed.isPresent()) {
            var existing = consumed.orElseThrow();
            if (!existing.getRequestFingerprint().equals(fingerprint)) throw new MessageIdentityConflictException();
            return new NotificationReceptionResult(message.messageId(), existing.getResult(),
                    existing.getNotificationId(), true);
        }
        var now = clock.instant();
        ReceptionStatus status;
        Notification notification = null;
        if (!now.isBefore(message.expiresAt())) {
            status = EXPIRED;
        } else if (!message.channels().contains(NotificationChannel.IN_APP)) {
            // Phase 5 has no settings/Endpoint API: IN_APP defaults to allowed and PUSH to denied.
            status = CHANNEL_DISABLED;
        } else {
            var existing = notifications.findByUserIdAndSourceEventId(message.userId(), message.sourceEventId());
            if (existing.isPresent()) {
                notification = existing.orElseThrow();
                status = DUPLICATE_EVENT;
            } else {
                notification = notifications.saveAndFlush(Notification.inApp(message, now));
                status = CREATED;
            }
        }
        consumedMessages.saveAndFlush(ConsumedMessage.record(message, fingerprint, status, notification, now));
        return new NotificationReceptionResult(message.messageId(), status,
                notification == null ? null : notification.getId(), false);
    }
}
