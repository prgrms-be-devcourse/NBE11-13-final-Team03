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
    private final NotificationSettingRepository settings;
    private final NotificationUserGuard guard;
    private final NotificationDeliveryQueue deliveries;

    public NotificationReceiveTransaction(NotificationRepository notifications,
                                           ConsumedMessageRepository consumedMessages, Clock clock,
                                           NotificationSettingRepository settings, NotificationUserGuard guard,
                                           NotificationDeliveryQueue deliveries) {
        this.notifications = notifications;
        this.consumedMessages = consumedMessages;
        this.clock = clock;
        this.settings = settings;
        this.guard = guard;
        this.deliveries = deliveries;
    }

    /** Separate bean/transaction so a uniqueness conflict is fully rolled back before duplicate lookup. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationReceptionResult receive(NotificationRequestedMessage message, String fingerprint) {
        guard.lock(message.userId());
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
        if (guard.isWithdrawn(message.userId())) {
            status = WITHDRAWN;
        } else if (!now.isBefore(message.expiresAt())) {
            status = EXPIRED;
        } else {
            var setting = settings.findById(message.userId());
            boolean inApp = message.channels().contains(NotificationChannel.IN_APP)
                    && setting.map(s -> s.isInAppEnabled()).orElse(true);
            boolean push = message.channels().contains(NotificationChannel.PUSH)
                    && setting.map(s -> s.isPushEnabled()).orElse(false)
                    && deliveries.hasActiveEndpoint(message.userId());
            if (!inApp && !push) {
                status = CHANNEL_DISABLED;
            } else {
                var existing = notifications.findByUserIdAndSourceEventId(message.userId(), message.sourceEventId());
                if (existing.isPresent()) {
                    notification = existing.orElseThrow();
                    status = DUPLICATE_EVENT;
                    // Never add channels/endpoints retroactively to a previously consumed event.
                } else {
                    notification = notifications.saveAndFlush(Notification.create(message, now, inApp));
                    if (push) deliveries.enqueue(notification, now);
                    status = CREATED;
                }
            }
        }
        consumedMessages.saveAndFlush(ConsumedMessage.record(message, fingerprint, status, notification, now));
        return new NotificationReceptionResult(message.messageId(), status,
                notification == null ? null : notification.getId(), false);
    }
}
