package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.DateTrackingProperties;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

@Slf4j
@Service
@RequiredArgsConstructor
public class DateTrackingService {
    private final DateTrackingReadiness readiness;
    private final EventScanHistoryRepository histories;
    private final EventScanCoordinator coordinator;
    private final DateTrackingBatch batch;
    private final DateTrackingProperties properties;
    private final Clock clock;

    /** No long encompassing transaction: each policy page commits independently. */
    public void runDueScans() {
        var now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        LocalTime time = LocalTime.ofInstant(now, KST);
        RuntimeException firstFailure = null;
        // Today goes first and outside the recovery budget so failing past dates cannot block it.
        if (!time.isBefore(properties.cutoff()) || readiness.ready(today)) {
            firstFailure = scanIfDue(today, today);
        }
        // Late D-N events are suppressed anyway, so only recent dates are worth recovering.
        LocalDate first = properties.startDate() != null ? properties.startDate()
                : histories.findFirstByOrderByScanDateAsc().map(h -> h.getScanDate()).orElse(today);
        LocalDate recoveryStart = today.minusDays(properties.recoveryDays());
        if (first.isBefore(recoveryStart)) {
            first = recoveryStart;
        }
        int started = 0;
        for (LocalDate date = first; date.isBefore(today) && started < properties.maxDatesPerRun();
             date = date.plusDays(1)) {
            // Only dates that actually acquired a run count toward the budget.
            var execution = start(date);
            if (execution.isEmpty()) {
                continue;
            }
            started++;
            var failure = scan(execution.orElseThrow(), today);
            if (firstFailure == null) {
                firstFailure = failure;
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private RuntimeException scanIfDue(LocalDate date, LocalDate today) {
        return start(date).map(execution -> scan(execution, today)).orElse(null);
    }

    private Optional<EventScanExecution> start(LocalDate date) {
        if (histories.existsByScanDateAndStatus(date, EventScanStatus.SUCCESS)) {
            return Optional.empty();
        }
        return coordinator.tryStart(date, clock.instant(), properties.leaseTimeout());
    }

    /** Returns the failure instead of throwing so the remaining dates still run. */
    private RuntimeException scan(EventScanExecution execution, LocalDate today) {
        long afterId = 0;
        try {
            while (true) {
                // Fixed for the whole run so pages after midnight keep the same notification eligibility.
                var result = batch.scan(execution, afterId, today);
                if (result.complete()) {
                    return null;
                }
                afterId = result.afterId();
            }
        } catch (RuntimeException exception) {
            log.error("Date tracking scan failed: scanDate={}, afterId={}", execution.scanDate(), afterId, exception);
            coordinator.fail(execution, clock.instant(), FailureReasons.of(exception));
            return exception;
        }
    }
}
