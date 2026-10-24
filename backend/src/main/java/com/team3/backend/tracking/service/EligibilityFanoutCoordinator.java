package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;
import static com.team3.backend.tracking.domain.entity.OpportunityEventProcessingStatus.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"yon.tracking.eligibility-fanout.enabled", "yon.tracking.eligibility-outbox.enabled"}, havingValue = "true")
public class EligibilityFanoutCoordinator {
    private static final Set<OpportunityEventType> TYPES = Set.of(OpportunityEventType.ELIGIBILITY_CHANGED);
    private final OpportunityEventRepository events;
    private final EventFanoutProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> candidates() {
        var now = clock.instant();
        return events.findFanoutCandidates(now, now.minus(properties.leaseTimeout()), properties.maxAttempts(),
                PENDING, PROCESSING, TYPES, PageRequest.of(0, properties.maxEventsPerRun()));
    }

    @Transactional
    public Optional<EventFanoutExecution> claim(Long eventId) {
        var now = clock.instant(); var token = UUID.randomUUID();
        return events.claimFanout(eventId, token, now, now.minus(properties.leaseTimeout()), properties.maxAttempts(),
                PENDING, PROCESSING, TYPES) == 1 ? Optional.of(new EventFanoutExecution(eventId, token)) : Optional.empty();
    }

    @Transactional
    public int recoverExhausted() {
        var now = clock.instant();
        return events.failExhaustedFanouts(now, now.minus(properties.leaseTimeout()), properties.maxAttempts(),
                PENDING, PROCESSING, FAILED, TYPES);
    }

    @Transactional
    public boolean retry(EventFanoutExecution execution, RuntimeException failure) {
        var event = events.lockForFanout(execution.eventId()).orElseThrow();
        if (event.getEventType() != OpportunityEventType.ELIGIBILITY_CHANGED || !event.ownedBy(execution.leaseToken())) return false;
        event.retryFanout(clock.instant(), properties.retryDelay(), FailureReasons.of(failure), properties.maxAttempts());
        return true;
    }
}
