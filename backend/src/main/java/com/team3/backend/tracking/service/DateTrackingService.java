package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.DateTrackingProperties;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

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
        // Historical recovery is allowed even while today's collection is incomplete.
        boolean todayReady = !time.isBefore(properties.cutoff()) || readiness.ready(today);
        LocalDate first = properties.startDate() != null ? properties.startDate()
                : histories.findFirstByOrderByScanDateAsc().map(h -> h.getScanDate()).orElse(today);
        int attempted = 0;
        RuntimeException firstFailure = null;
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            if (date.equals(today) && !todayReady) {
                break;
            }
            if (histories.existsByScanDateAndStatus(date, EventScanStatus.SUCCESS)) {
                continue;
            }
            if (attempted++ >= properties.maxDatesPerRun()) {
                break;
            }
            var execution = coordinator.tryStart(date, clock.instant(), properties.leaseTimeout());
            if (execution.isEmpty()) {
                continue;
            }
            try {
                long afterId = 0;
                while (true) {
                    var result = batch.scan(execution.orElseThrow(), afterId);
                    if (result.complete()) {
                        break;
                    }
                    afterId = result.afterId();
                }
            } catch (RuntimeException exception) {
                coordinator.fail(execution.orElseThrow(), clock.instant(), exception.getClass().getSimpleName());
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
