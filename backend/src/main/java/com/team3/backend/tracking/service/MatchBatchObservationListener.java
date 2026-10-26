package com.team3.backend.tracking.service;

import com.team3.backend.eligibility.event.EligibilityResultsRefreshed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Observe committed current results only. Produces pending intents, never Outbox/publication. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="yon.tracking.match.enabled",havingValue="true")
public class MatchBatchObservationListener {
    private static final int PAGE_SIZE=100;
    private final MatchCandidatePage pages;

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void refreshed(EligibilityResultsRefreshed event) {
        long cursor=0;
        try {
            MatchCandidatePage.Run page;
            do {
                page=pages.scanUser(event.userId(),cursor,PAGE_SIZE);
                if (page.failed()>0) {
                    log.warn("Match batch observation needs next full sweep: userId={}, failed={}",event.userId(),page.failed());
                }
                if (page.hasNext() && page.lastOpportunityId()<=cursor)
                    throw new IllegalStateException("Match page cursor did not advance");
                cursor=page.lastOpportunityId();
            } while (page.hasNext());
        } catch (RuntimeException failure) {
            // No durable event queue here: every successful later batch (including zero writes) rescans from zero.
            log.warn("Match batch observation deferred: userId={}, error={}",event.userId(),failure.getClass().getSimpleName());
        }
    }
}
