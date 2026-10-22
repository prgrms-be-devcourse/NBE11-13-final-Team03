package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EventFanoutProperties;
import com.team3.backend.tracking.domain.model.EventFanoutExecution;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.team3.backend.tracking.domain.entity.OpportunityEventProcessingStatus.*;

@Service
@RequiredArgsConstructor
public class EventFanoutCoordinator {
    private final OpportunityEventRepository events;
    private final EventFanoutProperties properties;
    private final Clock clock;

    @Transactional
    public List<Long> candidates() {
        var now = clock.instant();
        var staleBefore = now.minus(properties.leaseTimeout());
        events.failExhaustedFanouts(now, staleBefore, properties.maxAttempts(), PENDING, PROCESSING, FAILED);
        return events.findFanoutCandidates(now, staleBefore, properties.maxAttempts(), PENDING, PROCESSING,
                PageRequest.of(0, properties.maxEventsPerRun()));
    }

    @Transactional
    public Optional<EventFanoutExecution> claim(Long eventId) {
        var now = clock.instant();
        var token = UUID.randomUUID();
        return events.claimFanout(eventId, token, now, now.minus(properties.leaseTimeout()),
                properties.maxAttempts(), PENDING, PROCESSING) == 1
                ? Optional.of(new EventFanoutExecution(eventId, token)) : Optional.empty();
    }

    @Transactional
    public boolean retry(EventFanoutExecution execution, RuntimeException failure) {
        var event = events.lockForFanout(execution.eventId()).orElseThrow();
        if (!event.ownedBy(execution.leaseToken())) return false;
        event.retryFanout(clock.instant(), properties.retryDelay(), failure.getClass().getSimpleName(),
                properties.maxAttempts());
        return true;
    }
}
