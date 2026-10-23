package com.team3.backend.tracking.service;

/** Failure text stored in last_error: exception type plus message, capped so a long message cannot bloat the row. */
final class FailureReasons {
    private static final int MAX_LENGTH = 500;

    private FailureReasons() {
    }

    static String of(RuntimeException exception) {
        String reason = exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return reason.length() <= MAX_LENGTH ? reason : reason.substring(0, MAX_LENGTH);
    }
}
