package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.VersionTrackingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded discovery/comparison/analysis only. Does not infer baselines, publish or fan out users. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"yon.tracking.version.enabled", "yon.tracking.version-analysis.enabled"}, havingValue = "true")
public class VersionTrackingPipeline {
    private final OpportunityVersionWorker versions;
    private final OpportunityVersionAnalysisTransaction analyses;
    private final VersionTrackingProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long cursor;

    /** No enclosing transaction: comparison and analysis use their own durable transaction boundaries. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Run runOnce() {
        if (!running.compareAndSet(false, true)) return new Run(true, cursor, 0, 0, 0);
        try {
            var page = versions.runOnce(cursor, properties.maxPoliciesPerRun());
            int calls = 0; int failures = 0;
            for (var attempt : page.attempts()) {
                if (attempt.step().isEmpty()) { failures++; continue; }
                var step = attempt.step().orElseThrow();
                if (step.outcome() != OpportunityVersionComparisonTransaction.Outcome.COMPARED
                        && step.outcome() != OpportunityVersionComparisonTransaction.Outcome.WAITING_FOR_EVENT_ANALYSIS) continue;
                try {
                    calls++;
                    analyses.analyze(step.versionId());
                } catch (RuntimeException failure) {
                    failures++;
                    log.warn("Version analysis failed: opportunityId={}, versionId={}, error={}",
                            attempt.opportunityId(), step.versionId(), failure.getClass().getSimpleName());
                    if (log.isDebugEnabled()) log.debug("Version analysis diagnostic: versionId={}, trace={}",
                            step.versionId(), FailureReasons.diagnosticTrace(failure));
                }
            }
            cursor = page.hasNext() ? page.lastOpportunityId() : 0;
            return new Run(false, cursor, page.attempts().size(), calls, failures);
        } finally {
            running.set(false);
        }
    }

    /** Cursor is only a fair-sweep hint; persisted tracking rows remain authoritative after restart. */
    public record Run(boolean alreadyRunning, long nextCursor, int visitedPolicies, int analysisCalls, int failures) { }
}
