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

    /** Debug diagnostics without exception messages, SQL parameters or remote response bodies. */
    static String diagnosticTrace(RuntimeException exception) {
        var trace = new StringBuilder();
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            trace.append(cause.getClass().getName()).append('\n');
            var frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(12, frames.length); i++) trace.append("  at ").append(frames[i]).append('\n');
        }
        return trace.toString();
    }
}
