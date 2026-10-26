package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EligibilityFanoutSchedulingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded manual/scheduled execution. Successful pages are durable; yielding is not a failed attempt. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"yon.tracking.eligibility-fanout.enabled", "yon.tracking.eligibility-outbox.enabled"}, havingValue = "true")
public class EligibilityFanoutWorker {
    private final EligibilityFanoutCoordinator coordinator;
    private final EligibilityFanoutBatch batch;
    private final EligibilityFanoutSchedulingProperties scheduling;
    private final AtomicBoolean running = new AtomicBoolean();

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Run runOnce() {
        if (!running.compareAndSet(false, true)) return new Run(true, 0, 0, 0, 0);
        int claimedCount = 0; int pages = 0; int yielded = 0; int failures = 0;
        try {
            coordinator.recoverExhausted();
            for (Long id : coordinator.candidates()) {
                if (pages >= scheduling.maxPagesPerRun()) break;
                var claimed = coordinator.claim(id);
                if (claimed.isEmpty()) continue;
                claimedCount++;
                var execution = claimed.orElseThrow();
                try {
                    boolean done = false;
                    int eventPages = 0;
                    while (!done && pages < scheduling.maxPagesPerRun() && eventPages < scheduling.maxPagesPerEvent()) {
                        pages++; eventPages++; // Count failed and terminal/empty page calls too.
                        done = batch.process(execution);
                    }
                    if (!done && coordinator.yieldExecution(execution)) yielded++;
                } catch (RuntimeException failure) {
                    failures++;
                    // Failure to persist retry must not prevent processing unrelated candidates. Lease recovery remains available.
                    boolean retained = false;
                    try { retained = coordinator.retry(execution, failure); }
                    catch (RuntimeException retryFailure) {
                        log.warn("Eligibility fanout retry persistence failed: eventId={}, error={}", id, retryFailure.getClass().getSimpleName());
                        if (log.isDebugEnabled()) log.debug("Eligibility fanout retry diagnostic: eventId={}, trace={}", id, FailureReasons.diagnosticTrace(retryFailure));
                    }
                    log.warn("Eligibility fanout failed: eventId={}, retryOwned={}, error={}", id, retained, failure.getClass().getSimpleName());
                    if (log.isDebugEnabled()) log.debug("Eligibility fanout diagnostic: eventId={}, trace={}", id, FailureReasons.diagnosticTrace(failure));
                }
            }
            return new Run(false, claimedCount, pages, yielded, failures);
        } finally {
            running.set(false);
        }
    }

    /** Page calls are bounded work, not successful-user or delivery counts. */
    public record Run(boolean alreadyRunning, int claimedEvents, int pageCalls, int yieldedEvents, int failures) { }
}
