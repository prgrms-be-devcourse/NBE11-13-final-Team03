package com.team3.backend.tracking.service;

import com.team3.backend.tracking.domain.entity.EventScanStatus;
import com.team3.backend.tracking.domain.model.EventScanExecution;
import com.team3.backend.tracking.domain.repository.EventScanHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventScanCoordinator {

    private final EventScanHistoryRepository historyRepository;

    @Transactional
    public Optional<EventScanExecution> tryStart(LocalDate scanDate, Instant now, Duration leaseTimeout) {
        if (leaseTimeout.isZero() || leaseTimeout.isNegative()) {
            throw new IllegalArgumentException("leaseTimeout must be positive");
        }
        var started = tryStart(scanDate, now);
        if (started.isPresent()) {
            return started;
        }
        String executionId = UUID.randomUUID().toString();
        return historyRepository.restartExpired(scanDate, executionId, now, now.minus(leaseTimeout),
                EventScanStatus.RUNNING) == 1
                ? Optional.of(new EventScanExecution(scanDate, executionId)) : Optional.empty();
    }

    @Transactional
    public Optional<EventScanExecution> tryStart(
            LocalDate scanDate,
            Instant now
    ) {
        String executionId =
                UUID.randomUUID().toString();

        int inserted =
                historyRepository.insertRunningIfAbsent(
                        scanDate,
                        executionId,
                        now
                );

        if (inserted == 1) {
            return Optional.of(
                    new EventScanExecution(
                            scanDate,
                            executionId
                    )
            );
        }

        int restarted =
                historyRepository.restartFailed(
                        scanDate,
                        executionId,
                        now,
                        EventScanStatus.RUNNING,
                        EventScanStatus.FAILED
                );

        if (restarted == 1) {
            return Optional.of(
                    new EventScanExecution(
                            scanDate,
                            executionId
                    )
            );
        }

        return Optional.empty();
    }

    @Transactional
    public boolean complete(
            EventScanExecution execution,
            Instant now
    ) {
        return historyRepository.completeIfOwned(
                execution.scanDate(),
                execution.executionId(),
                now,
                EventScanStatus.RUNNING,
                EventScanStatus.SUCCESS
        ) == 1;
    }

    @Transactional
    public boolean fail(
            EventScanExecution execution,
            Instant now,
            String errorMessage
    ) {
        return historyRepository.failIfOwned(
                execution.scanDate(),
                execution.executionId(),
                now,
                errorMessage,
                EventScanStatus.RUNNING,
                EventScanStatus.FAILED
        ) == 1;
    }
}
