package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit invocation only. Successful pages are durable; no automatic scheduling or activation. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"yon.tracking.eligibility-fanout.enabled", "yon.tracking.eligibility-outbox.enabled"}, havingValue = "true")
public class EligibilityFanoutWorker {
    private final EligibilityFanoutCoordinator coordinator;
    private final EligibilityFanoutBatch batch;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        coordinator.recoverExhausted();
        for (Long id : coordinator.candidates()) {
            var claimed = coordinator.claim(id);
            if (claimed.isEmpty()) continue;
            var execution = claimed.orElseThrow();
            try {
                while (!batch.process(execution)) { /* Separate page transaction and heartbeat. */ }
            } catch (RuntimeException failure) {
                boolean retained = coordinator.retry(execution, failure);
                log.warn("Eligibility fanout failed: eventId={}, retryOwned={}, error={}", id, retained, failure.getClass().getSimpleName());
            }
        }
    }
}
