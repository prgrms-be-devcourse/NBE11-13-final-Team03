package com.team3.backend.tracking.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class MatchSummaryWindowTest {
    @Test
    void opensAt0830AndClosesAt0900Kst() {
        assertThat(MatchSummaryWindow.current(Instant.parse("2026-10-25T23:29:59Z"))).isNull();
        assertThat(MatchSummaryWindow.current(Instant.parse("2026-10-25T23:30:00Z"))).isNotNull();
        assertThat(MatchSummaryWindow.current(Instant.parse("2026-10-25T23:59:59Z"))).isNotNull();
        assertThat(MatchSummaryWindow.current(Instant.parse("2026-10-26T00:00:00Z"))).isNull();
    }

    @Test
    void expiryIsNextDayNineAndRunIdIsStableByUserAndKstDate() {
        var first=MatchSummaryWindow.current(Instant.parse("2026-10-25T23:30:00Z"));
        var retry=MatchSummaryWindow.current(Instant.parse("2026-10-25T23:50:00Z"));
        var nextDay=MatchSummaryWindow.current(Instant.parse("2026-10-26T23:30:00Z"));
        assertThat(first.expiresAt()).isEqualTo(Instant.parse("2026-10-27T00:00:00Z"));
        assertThat(first.runId(7)).isEqualTo(retry.runId(7)).isNotEqualTo(first.runId(8))
                .isNotEqualTo(nextDay.runId(7));
    }
}
