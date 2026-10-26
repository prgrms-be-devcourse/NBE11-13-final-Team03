package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.tracking.match.enabled:false} && "
        + "${yon.tracking.match.outbox.enabled:false} && ${yon.tracking.match.automatic.enabled:false} && "
        + "${yon.tracking.match.automatic.scheduler-enabled:false}")
public class MatchAutomaticScheduler {
    private final MatchAutomaticWorker worker;

    @Scheduled(fixedDelayString="${yon.tracking.match.automatic.poll-interval-ms:60000}")
    public void run() {
        try {
            var result=worker.runOnce();
            log.debug("Match publication poll: alreadyRunning={}, transitions={}, summaries={}, failures={}",
                    result.alreadyRunning(),result.transitionCalls(),result.summaryCalls(),result.failures());
        } catch (RuntimeException failure) {
            log.error("Match publication poll failed: error={}",failure.getClass().getSimpleName());
            if (log.isDebugEnabled()) log.debug("Match publication poll diagnostic: trace={}",FailureReasons.diagnosticTrace(failure));
        }
    }
}
