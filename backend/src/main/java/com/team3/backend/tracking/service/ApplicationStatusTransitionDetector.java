package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.tracking.domain.entity.OpportunityEventType;
import com.team3.backend.tracking.domain.model.ApplicationStatusTransition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ApplicationStatusTransitionDetector {

    private final ApplicationStatusCalculator applicationStatusCalculator;

    public Optional<ApplicationStatusTransition> detect(
            Collection<ApplicationPeriod> periods,
            LocalDate today
    ) {
        Objects.requireNonNull(periods, "periods must not be null");
        Objects.requireNonNull(today, "today must not be null");

        ApplicationStatus previousStatus =
                applicationStatusCalculator.calculate(
                        periods,
                        today.minusDays(1)
                );

        ApplicationStatus currentStatus =
                applicationStatusCalculator.calculate(
                        periods,
                        today
                );

        OpportunityEventType eventType =
                resolveEventType(previousStatus, currentStatus);

        if (eventType == null) {
            return Optional.empty();
        }

        return Optional.of(
                new ApplicationStatusTransition(
                        today,
                        previousStatus,
                        currentStatus,
                        eventType
                )
        );
    }

    private OpportunityEventType resolveEventType(
            ApplicationStatus previousStatus,
            ApplicationStatus currentStatus
    ) {
        if (previousStatus == ApplicationStatus.UPCOMING
                && currentStatus == ApplicationStatus.OPEN) {
            return OpportunityEventType.APPLICATION_OPENED;
        }

        if (previousStatus == ApplicationStatus.OPEN
                && currentStatus == ApplicationStatus.CLOSED) {
            return OpportunityEventType.APPLICATION_CLOSED;
        }

        return null;
    }
}
