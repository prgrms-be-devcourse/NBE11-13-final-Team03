package com.team3.notification.service;

import com.team3.notification.domain.entity.ConsumedMessage;
import com.team3.notification.domain.entity.ReceptionStatus;
import com.team3.notification.domain.repository.ConsumedMessageRepository;
import com.team3.notification.message.UserSessionEndedMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

/**
 * Logout control: deactivates the push Endpoints registered by one login session.
 * Other sessions of the same user keep their Endpoints. The token row stays owned by the user and is
 * re-activated by the next registration, which also moves it to the session that registers it.
 */
@Service
public class NotificationSessionEndTransaction {
    private final NotificationUserGuard guard;
    private final NotificationUserService endpoints;
    private final ConsumedMessageRepository consumed;
    private final Clock clock;

    public NotificationSessionEndTransaction(NotificationUserGuard guard, NotificationUserService endpoints,
            ConsumedMessageRepository consumed, Clock clock) {
        this.guard = guard; this.endpoints = endpoints; this.consumed = consumed; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationReceptionResult receive(UserSessionEndedMessage message, String fingerprint) {
        // Same per-user lock as registration, so a logout and a concurrent re-registration serialize.
        guard.lock(message.userId());
        var previous = consumed.findByMessageId(message.messageId());
        if (previous.isPresent()) {
            var existing = previous.orElseThrow();
            if (!existing.getRequestFingerprint().equals(fingerprint)) throw new MessageIdentityConflictException();
            return new NotificationReceptionResult(message.messageId(), existing.getResult(), null, true);
        }
        // A withdrawn user's Endpoints are already deleted: the update touches nothing and the receipt still records consumption.
        endpoints.deactivateSessionEndpoints(message.userId(), message.sessionId());
        consumed.saveAndFlush(ConsumedMessage.sessionEnd(message, fingerprint, clock.instant()));
        return new NotificationReceptionResult(message.messageId(), ReceptionStatus.SESSION_ENDED, null, false);
    }
}
