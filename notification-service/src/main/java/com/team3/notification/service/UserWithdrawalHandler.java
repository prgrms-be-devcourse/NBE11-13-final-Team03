package com.team3.notification.service;

import com.team3.notification.message.*;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class UserWithdrawalHandler {
    private final Validator validator;
    private final NotificationMessageFingerprint fingerprints;
    private final NotificationWithdrawalTransaction transaction;
    public UserWithdrawalHandler(Validator validator, NotificationMessageFingerprint fingerprints,
                                 NotificationWithdrawalTransaction transaction) {
        this.validator = validator; this.fingerprints = fingerprints; this.transaction = transaction;
    }
    public NotificationReceptionResult handle(UserWithdrawnMessage message) {
        var violations = validator.validate(message);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        var fingerprint = fingerprints.fingerprint(message);
        try { return transaction.receive(message, fingerprint); }
        catch (DataIntegrityViolationException failure) {
            if (!NotificationMessageHandler.isExpectedDuplicate(failure)) throw failure;
            return transaction.receive(message, fingerprint);
        }
    }
}
