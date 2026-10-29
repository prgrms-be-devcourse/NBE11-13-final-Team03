package com.team3.notification.service;

import com.team3.notification.domain.repository.WithdrawnUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class NotificationUserGuard {
    private final JdbcTemplate jdbc;
    private final WithdrawnUserRepository withdrawn;
    public NotificationUserGuard(JdbcTemplate jdbc, WithdrawnUserRepository withdrawn) {
        this.jdbc = jdbc; this.withdrawn = withdrawn;
    }

    /** PostgreSQL transaction lock works even before any user/settings/tombstone row exists. */
    public void lock(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification user lock requires an active transaction");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", result -> { },
                "yon.notification.user:" + userId);
    }

    public boolean isWithdrawn(long userId) { return withdrawn.existsById(userId); }

    public void requireActive(long userId) {
        if (isWithdrawn(userId)) throw new NotificationUserApiException(410, "USER_WITHDRAWN");
    }

    public void lockActive(long userId) { lock(userId); requireActive(userId); }
}
