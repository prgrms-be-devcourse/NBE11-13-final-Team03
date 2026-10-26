package com.team3.backend.outbox.publisher;

/** A safe error code only: never retain Feign request bodies, credentials or remote error bodies. */
public class NotificationPublicationException extends RuntimeException {
    private final boolean retryable;
    private final boolean configuration;

    public NotificationPublicationException(String code, boolean retryable) {
        this(code, retryable, false);
    }

    private NotificationPublicationException(String code, boolean retryable, boolean configuration) {
        super(code);
        this.retryable = retryable;
        this.configuration = configuration;
    }

    /**
     * Wrong key, URL or route (401/403/404/3xx): every message would fail the same way,
     * so the message is not charged an attempt and the batch stops until the setting is fixed.
     */
    public static NotificationPublicationException configuration(String code) {
        return new NotificationPublicationException(code, true, true);
    }

    public boolean isRetryable() { return retryable; }

    public boolean isConfiguration() { return configuration; }
}
