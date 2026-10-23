package com.team3.backend.outbox.publisher;

/** A safe error code only: never retain Feign request bodies, credentials or remote error bodies. */
public class NotificationPublicationException extends RuntimeException {
    private final boolean retryable;

    public NotificationPublicationException(String code, boolean retryable) {
        super(code);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
