package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.MatchAutomaticProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persisted candidate status and deterministic summary run IDs survive restart; cursors only spread load. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name={"yon.tracking.match.enabled", "yon.tracking.match.outbox.enabled",
        "yon.tracking.match.automatic.enabled"}, havingValue="true")
public class MatchAutomaticWorker {
    private final JdbcTemplate jdbc;
    private final MatchOutboxTransaction outbox;
    private final MatchAutomaticProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long transitionCursor;
    private volatile long summaryUserCursor;

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Run runOnce() {
        if (!running.compareAndSet(false, true)) return new Run(true, 0, 0, 0);
        int transitions=0, summaries=0, failures=0;
        try {
            var transitionPage=transitionsAfter(transitionCursor,properties.maxTransitionsPerRun());
            for (var item:transitionPage) {
                transitionCursor=item.id();
                try {
                    outbox.transition(item.userId(),item.id());
                    transitions++;
                } catch (RuntimeException failure) {
                    failures++;
                    log.warn("Match transition publication failed: candidateId={}, error={}", item.id(), failure.getClass().getSimpleName());
                    if (log.isDebugEnabled()) log.debug("Match transition diagnostic: candidateId={}, trace={}",
                            item.id(), FailureReasons.diagnosticTrace(failure));
                }
            }
            if (transitionPage.size() < properties.maxTransitionsPerRun()) transitionCursor=0;

            var window=MatchSummaryWindow.current(clock.instant());
            if (window!=null) {
                var users=summaryUsersAfter(summaryUserCursor,properties.maxSummaryUsersPerRun());
                for (long userId:users) {
                    // Do not start a new user after the 09:00 KST creation window closes.
                    if (MatchSummaryWindow.current(clock.instant())==null) break;
                    summaryUserCursor=userId;
                    try {
                        outbox.summarize(userId,window.runId(userId),window.expiresAt());
                        summaries++;
                    } catch (RuntimeException failure) {
                        failures++;
                        log.warn("Match summary publication failed: userId={}, error={}", userId, failure.getClass().getSimpleName());
                        if (log.isDebugEnabled()) log.debug("Match summary diagnostic: userId={}, trace={}",
                                userId, FailureReasons.diagnosticTrace(failure));
                    }
                }
                if (users.size() < properties.maxSummaryUsersPerRun()) summaryUserCursor=0;
            }
            return new Run(false,transitions,summaries,failures);
        } finally { running.set(false); }
    }

    private List<Transition> transitionsAfter(long afterId,int limit) {
        return jdbc.query("SELECT c.id,p.user_id FROM notification_match_candidates c "
                        + "JOIN notification_match_pairs p ON p.id=c.pair_id "
                        + "WHERE c.status='PENDING' AND c.reason='TRANSITION' AND c.id>? "
                        + "ORDER BY c.id LIMIT ?",
                (rs,row)->new Transition(rs.getLong(1),rs.getLong(2)),afterId,limit);
    }

    private List<Long> summaryUsersAfter(long afterUserId,int limit) {
        return jdbc.queryForList("SELECT DISTINCT p.user_id FROM notification_match_candidates c "
                        + "JOIN notification_match_pairs p ON p.id=c.pair_id "
                        + "WHERE c.status='PENDING' AND c.reason='FIRST_MATCH' AND p.user_id>? "
                        + "ORDER BY p.user_id LIMIT ?",Long.class,afterUserId,limit);
    }

    record Transition(long id,long userId) { }
    public record Run(boolean alreadyRunning,int transitionCalls,int summaryCalls,int failures) { }
}
