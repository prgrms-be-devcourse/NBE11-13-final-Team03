package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.MatchAutomaticProperties;
import com.team3.backend.tracking.domain.repository.MatchCandidateEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    /** Team decision: count failures, never discard. From this many attempts the same candidate is reported as an error for operators. */
    static final int FAILURE_ALERT_ATTEMPTS=60;
    private static final String SUMMARY_MEMBER=MatchCandidateEventRepository.SUMMARY_MEMBER_SQL;

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
                    reportFailure("transition","candidateId="+item.id(),failure,recordTransitionFailure(item.id()));
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
                        reportFailure("summary","userId="+userId,failure,recordSummaryFailure(userId));
                    }
                }
                if (users.size() < properties.maxSummaryUsersPerRun()) summaryUserCursor=0;
            }
            return new Run(false,transitions,summaries,failures);
        } finally { running.set(false); }
    }

    /** Individual transitions only; profile-change transitions are summary members (PostgreSQL JSON operator). */
    private List<Transition> transitionsAfter(long afterId,int limit) {
        return jdbc.query("SELECT c.id,p.user_id FROM notification_match_candidates c "
                        + "JOIN notification_match_pairs p ON p.id=c.pair_id "
                        + "WHERE c.status='PENDING' AND c.reason='TRANSITION' AND NOT "+SUMMARY_MEMBER+" AND c.id>? "
                        + "ORDER BY c.id LIMIT ?",
                (rs,row)->new Transition(rs.getLong(1),rs.getLong(2)),afterId,limit);
    }

    private List<Long> summaryUsersAfter(long afterUserId,int limit) {
        return jdbc.queryForList("SELECT DISTINCT p.user_id FROM notification_match_candidates c "
                        + "JOIN notification_match_pairs p ON p.id=c.pair_id "
                        + "WHERE c.status='PENDING' AND "+SUMMARY_MEMBER+" AND p.user_id>? "
                        + "ORDER BY p.user_id LIMIT ?",Long.class,afterUserId,limit);
    }

    /** Auto-committed outside the rolled-back publication; returns the candidate's attempts so far, or null if even that failed. */
    private Integer recordTransitionFailure(long candidateId) {
        try {
            return jdbc.queryForObject("UPDATE notification_match_candidates SET attempt_count=attempt_count+1,last_failed_at=? "
                    + "WHERE id=? AND status='PENDING' RETURNING attempt_count",Integer.class,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),candidateId);
        } catch (RuntimeException recording) { return null; }
    }
    /** A summary failure counts against every pending member of that user's summary; the maximum is reported. */
    private Integer recordSummaryFailure(long userId) {
        try {
            return jdbc.queryForObject("WITH failed AS (UPDATE notification_match_candidates c SET attempt_count=c.attempt_count+1,last_failed_at=? "
                    + "FROM notification_match_pairs p WHERE p.id=c.pair_id AND p.user_id=? AND c.status='PENDING' AND "+SUMMARY_MEMBER
                    + " RETURNING c.attempt_count) SELECT COALESCE(MAX(attempt_count),0) FROM failed",
                    Integer.class,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),userId);
        } catch (RuntimeException recording) { return null; }
    }
    private void reportFailure(String kind,String subject,RuntimeException failure,Integer attempts) {
        if (attempts!=null && attempts>=FAILURE_ALERT_ATTEMPTS)
            log.error("Match {} publication keeps failing: {}, attempts={}, error={}. Inspect the candidate; it is never discarded automatically.",
                    kind,subject,attempts,failure.getClass().getSimpleName());
        else log.warn("Match {} publication failed: {}, attempts={}, error={}",kind,subject,attempts,failure.getClass().getSimpleName());
        if (log.isDebugEnabled()) log.debug("Match {} diagnostic: {}, trace={}",kind,subject,FailureReasons.diagnosticTrace(failure));
    }

    record Transition(long id,long userId) { }
    public record Run(boolean alreadyRunning,int transitionCalls,int summaryCalls,int failures) { }
}
