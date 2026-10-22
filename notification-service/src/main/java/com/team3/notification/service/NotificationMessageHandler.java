package com.team3.notification.service;

import com.team3.notification.message.*;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
public class NotificationMessageHandler {
    private static final Set<String> DUPLICATE_CONSTRAINTS =
            Set.of("uk_consumed_messages_message_id", "uk_notifications_user_source_event");
    private final NotificationMessageDecoder decoder;
    private final NotificationMessageValidator validator;
    private final NotificationMessageFingerprint fingerprints;
    private final NotificationReceiveTransaction transaction;

    public NotificationMessageHandler(NotificationMessageDecoder decoder, NotificationMessageValidator validator,
                                      NotificationMessageFingerprint fingerprints,
                                      NotificationReceiveTransaction transaction) {
        this.decoder = decoder;
        this.validator = validator;
        this.fingerprints = fingerprints;
        this.transaction = transaction;
    }

    public NotificationReceptionResult handle(String json) {
        return handle(decoder.decode(json));
    }

    public NotificationReceptionResult handle(NotificationRequestedMessage message) {
        validator.validate(message);
        String fingerprint = fingerprints.fingerprint(message);
        try {
            return transaction.receive(message, fingerprint);
        } catch (DataIntegrityViolationException failure) {
            if (!isExpectedDuplicate(failure)) throw failure;
            // receive() has already rolled back. Re-read committed records in a NEW transaction.
            // If another conflict/error occurs here it propagates; it is not converted into a success.
            return transaction.receive(message, fingerprint);
        }
    }

    static boolean isExpectedDuplicate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && violation.getConstraintName() != null
                    && DUPLICATE_CONSTRAINTS.contains(violation.getConstraintName())) return true;
        }
        return false;
    }
}
