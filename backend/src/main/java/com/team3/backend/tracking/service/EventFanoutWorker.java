package com.team3.backend.tracking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventFanoutWorker {
    private final EventFanoutCoordinator coordinator;
    private final EventFanoutBatch batch;

    /** No enclosing transaction: successful user pages remain durable across later failures. */
    public void runOnce() {
        coordinator.recoverExhausted();
        for (Long eventId : coordinator.candidates()) {
            var execution = coordinator.claim(eventId);
            if (execution.isEmpty()) continue;
            try {
                while (!batch.process(execution.orElseThrow())) {
                    // Each invocation crosses the separate transactional batch bean.
                }
            } catch (RuntimeException failure) {
                boolean retained = coordinator.retry(execution.orElseThrow(), failure);
                log.warn("Event fanout failed: eventId={}, leaseToken={}, retryOwned={}, error={}",
                        eventId, execution.orElseThrow().leaseToken(), retained, failure.getClass().getSimpleName());
            }
        }
    }
}
