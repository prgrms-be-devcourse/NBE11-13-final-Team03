package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.MatchAutomaticProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MatchAutomaticWorkerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final MatchOutboxTransaction outbox=mock(MatchOutboxTransaction.class);
    private final Instant morning=Instant.parse("2026-10-25T23:35:00Z");

    private MatchAutomaticWorker worker(Instant now) {
        return new MatchAutomaticWorker(jdbc,outbox,new MatchAutomaticProperties(true,false,2,2,60000),
                Clock.fixed(now,ZoneOffset.UTC));
    }

    @Test
    void morningUsesStableRunIdAndExpiryForEachUser() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<MatchAutomaticWorker.Transition>>any(),anyLong(),anyInt()))
                .thenReturn(List.of());
        when(jdbc.queryForList(anyString(),eq(Long.class),anyLong(),anyInt()))
                .thenReturn(List.of(7L,8L));
        when(outbox.summarize(anyLong(),any(java.util.UUID.class),any(Instant.class)))
                .thenReturn(new MatchOutboxTransaction.Result(MatchOutboxTransaction.Status.ENQUEUED,1L,1));
        var worker=worker(morning);
        assertThat(worker.runOnce().summaryCalls()).isEqualTo(2);
        var window=MatchSummaryWindow.current(morning);
        verify(outbox).summarize(7,window.runId(7),window.expiresAt());
        verify(outbox).summarize(8,window.runId(8),window.expiresAt());
        verify(outbox,never()).transition(anyLong(),anyLong());
    }

    @Test
    void outsideSummaryWindowStillScansTransitions() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<MatchAutomaticWorker.Transition>>any(),anyLong(),anyInt()))
                .thenReturn(List.of(new MatchAutomaticWorker.Transition(10,7),new MatchAutomaticWorker.Transition(11,8)));
        when(outbox.transition(7,10)).thenThrow(new IllegalStateException("retry later"));
        var run=worker(Instant.parse("2026-10-26T01:00:00Z")).runOnce();
        assertThat(run.transitionCalls()).isEqualTo(1);
        assertThat(run.failures()).isEqualTo(1);
        assertThat(run.summaryCalls()).isZero();
        verify(outbox).transition(8,11);
        verify(jdbc,never()).queryForList(anyString(),eq(Long.class),anyLong(),anyInt());
    }

    @Test
    void defaultsRemainDisabledAndBudgetsAreBounded() {
        var defaults=new MatchAutomaticProperties(false,false,25,25,60000);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.schedulerEnabled()).isFalse();
        assertThatThrownBy(()->new MatchAutomaticProperties(true,true,0,25,60000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
