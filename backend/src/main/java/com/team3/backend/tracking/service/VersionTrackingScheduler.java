package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${app.scheduling.enabled:true} && ${yon.tracking.version.enabled:false} && ${yon.tracking.version-analysis.enabled:false}")
public class VersionTrackingScheduler {
    private final VersionTrackingPipeline pipeline;

    @Scheduled(fixedDelayString = "${yon.tracking.version.poll-interval-ms:60000}")
    public void run() {
        try {
            var result = pipeline.runOnce();
            log.debug("Version tracking poll: alreadyRunning={}, nextCursor={}, visitedPolicies={}, analysisCalls={}, failures={}",
                    result.alreadyRunning(), result.nextCursor(), result.visitedPolicies(), result.analysisCalls(), result.failures());
        } catch (RuntimeException failure) {
            log.error("Version tracking poll failed: error={}", failure.getClass().getSimpleName());
            if (log.isDebugEnabled()) log.debug("Version tracking poll diagnostic: trace={}", FailureReasons.diagnosticTrace(failure));
        }
    }
}
