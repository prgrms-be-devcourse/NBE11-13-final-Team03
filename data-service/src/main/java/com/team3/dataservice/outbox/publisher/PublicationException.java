package com.team3.dataservice.outbox.publisher;

/** A safe error code only: never retain credentials, request bodies or remote error bodies. */
public class PublicationException extends RuntimeException {
    private final boolean retryable;
    private final boolean configuration;

    public PublicationException(String code, boolean retryable) {
        this(code, retryable, false);
    }

    private PublicationException(String code, boolean retryable, boolean configuration) {
        super(code);
        this.retryable = retryable;
        this.configuration = configuration;
    }

    /**
     * Missing credentials, wrong queue or denied access: every message would fail the same way,
     * so the message is not charged an attempt and the batch stops until the setting is fixed.
     */
    public static PublicationException configuration(String code) {
        return new PublicationException(code, true, true);
    }

    public boolean isRetryable() { return retryable; }

    public boolean isConfiguration() { return configuration; }
}
