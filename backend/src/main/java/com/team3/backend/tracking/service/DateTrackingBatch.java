package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.tracking.config.DateTrackingProperties;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.entity.OpportunityEvent;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import com.team3.backend.tracking.domain.repository.OpportunityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

@Service
@RequiredArgsConstructor
public class DateTrackingBatch {
    private final EventScanHistoryRepository histories;
    private final OpportunityRepository opportunities;
    private final ApplicationWindowRepository windows;
    private final OpportunityEventRepository events;
    private final ApplicationStatusTransitionDetector transitions;
    private final DeadlineSoonDetector deadlines;
    private final OpportunityEventFactory factory;
    private final DateTrackingProperties properties;
    private final Clock clock;

    /** Lock execution ownership through page commit, including event inserts and heartbeat. */
    @Transactional
    public PageResult scan(EventScanExecution execution, long afterId) {
        var history = histories.lockByScanDate(execution.scanDate()).orElseThrow();
        if (history.getStatus() != EventScanStatus.RUNNING
                || !history.getExecutionId().equals(execution.executionId())) {
            throw new IllegalStateException("Date scan ownership lost");
        }
        var page = opportunities.findDateTrackingCandidates(afterId, OpportunityType.YOUTH_POLICY,
                PageRequest.of(0, properties.batchSize()));
        if (page.isEmpty()) {
            history.succeed(clock.instant());
            return new PageResult(afterId, true);
        }
        var periodsByVersion = windows.findByVersionIdIn(page.stream()
                .map(o -> o.getCurrentVersion().getId()).toList()).stream()
                .collect(Collectors.groupingBy(w -> w.getVersion().getId(),
                        Collectors.mapping(w -> new ApplicationPeriod(w.getPeriodType(), w.getStartDate(),
                                w.getEndDate(), w.getValidationStatus(), w.getPeriodText()), Collectors.toList())));
        LocalDate processingDate = LocalDate.ofInstant(clock.instant(), KST);
        for (var opportunity : page) {
            var version = opportunity.getCurrentVersion();
            var periods = periodsByVersion.getOrDefault(version.getId(), List.of());
            transitions.detect(periods, execution.scanDate()).ifPresent(transition ->
                    saveIfAbsent(factory.createStatusTransitionEvent(opportunity, version, transition,
                            clock.instant()), execution));
            for (var candidate : deadlines.detect(periods, execution.scanDate())) {
                saveIfAbsent(factory.createDeadlineSoonEvent(opportunity, version, candidate, processingDate,
                        clock.instant()), execution);
            }
        }
        events.flush();
        history.heartbeat(clock.instant());
        return new PageResult(page.getLast().getId(), false);
    }

    private void saveIfAbsent(OpportunityEvent event, EventScanExecution execution) {
        if (!events.existsByDedupKey(event.getDedupKey())) {
            event.recordOrigin(UUID.fromString(execution.executionId()),
                    execution.scanDate().atStartOfDay(KST).toInstant());
            events.save(event);
        }
    }

    public record PageResult(long afterId, boolean complete) {
    }
}
