package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.DateTrackingProperties;
import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import static com.team3.backend.tracking.config.DateTrackingConfig.KST;

@Slf4j
@Service
@RequiredArgsConstructor
public class DateTrackingService {
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

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
            long afterId = 0;
            try {
                while (true) {
                    // Fixed for the whole run so pages after midnight keep the same notification eligibility.
                    var result = batch.scan(execution.orElseThrow(), afterId, today);
                    if (result.complete()) {
                        break;
                    }
                    afterId = result.afterId();
                }
            } catch (RuntimeException exception) {
                log.error("Date tracking scan failed: scanDate={}, afterId={}", date, afterId, exception);
                coordinator.fail(execution.orElseThrow(), clock.instant(), failureReason(exception));
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static String failureReason(RuntimeException exception) {
        String reason = exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return reason.length() <= MAX_FAILURE_REASON_LENGTH ? reason : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
