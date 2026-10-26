package com.team3.backend.tracking.service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

/** Provisional daily summary policy. A retry uses the same KST-day/user run identifier. */
public final class MatchSummaryWindow {
    private static final LocalTime START = LocalTime.of(8, 30);
    private static final LocalTime END = LocalTime.of(9, 0);

    private MatchSummaryWindow() { }

    public static Window current(Instant now) {
        var local = now.atZone(KST);
        if (local.toLocalTime().isBefore(START) || !local.toLocalTime().isBefore(END)) return null;
        var date = local.toLocalDate();
        return new Window(date, date.plusDays(1).atTime(END).atZone(KST).toInstant());
    }

    public record Window(LocalDate date, Instant expiresAt) {
        public UUID runId(long userId) {
            if (userId < 1) throw new IllegalArgumentException("Persisted user ID required");
            return UUID.nameUUIDFromBytes(("NEW_MATCH:" + date + ":" + userId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
