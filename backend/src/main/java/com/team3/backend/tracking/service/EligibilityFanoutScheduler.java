package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.tracking.eligibility-fanout.enabled:false} && ${yon.tracking.eligibility-outbox.enabled:false} && ${yon.tracking.eligibility-fanout.scheduler.enabled:false}")
public class EligibilityFanoutScheduler {
    private final EligibilityFanoutWorker worker;

    @Scheduled(fixedDelayString = "${yon.tracking.eligibility-fanout.scheduler.poll-interval-ms:60000}")
    public void run() {
        try {
            var result = worker.runOnce();
            log.debug("Eligibility fanout poll: alreadyRunning={}, claimedEvents={}, pageCalls={}, yieldedEvents={}, failures={}",
                    result.alreadyRunning(), result.claimedEvents(), result.pageCalls(), result.yieldedEvents(), result.failures());
        } catch (RuntimeException failure) {
            log.error("Eligibility fanout poll failed: error={}", failure.getClass().getSimpleName());
            if (log.isDebugEnabled()) log.debug("Eligibility fanout poll diagnostic: trace={}", FailureReasons.diagnosticTrace(failure));
        }
    }
}
