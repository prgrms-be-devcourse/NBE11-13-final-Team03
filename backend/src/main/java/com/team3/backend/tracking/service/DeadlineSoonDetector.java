package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.tracking.domain.model.DeadlineSoonCandidate;
import com.team3.backend.tracking.domain.model.DeadlineThreshold;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class DeadlineSoonDetector {

    private final ApplicationStatusCalculator applicationStatusCalculator;

    public List<DeadlineSoonCandidate> detect(
            Collection<ApplicationPeriod> periods,
            LocalDate scanDate
    ) {
        Objects.requireNonNull(periods, "periods must not be null");
        Objects.requireNonNull(scanDate, "scanDate must not be null");

        if (containsValidAlwaysOpenPeriod(periods)) {
            return List.of();
        }

        ApplicationStatus currentStatus =
                applicationStatusCalculator.calculate(
                        periods,
                        scanDate
                );

        if (currentStatus != ApplicationStatus.OPEN) {
            return List.of();
        }

        return periods.stream()
                .filter(this::isValidFixedPeriod)
                .filter(period ->
                        isOpenOn(period, scanDate)
                )
                .map(period ->
                        toCandidate(period, scanDate)
                )
                .flatMap(java.util.Optional::stream)
                .distinct()
                .sorted(
                        Comparator.comparing(
                                DeadlineSoonCandidate::endDate
                        )
                )
                .toList();
    }

    private boolean containsValidAlwaysOpenPeriod(
            Collection<ApplicationPeriod> periods
    ) {
        return periods.stream()
                .anyMatch(period ->
                        period.validationStatus()
                                == ValidationStatus.VALID
                                && period.periodType()
                                == PeriodType.ALWAYS
                );
    }

    private boolean isValidFixedPeriod(
            ApplicationPeriod period
    ) {
        return period.validationStatus()
                == ValidationStatus.VALID
                && period.periodType()
                == PeriodType.FIXED
                && period.endDate() != null;
    }

    private boolean isOpenOn(
            ApplicationPeriod period,
            LocalDate scanDate
    ) {
        LocalDate startDate = period.startDate();
        LocalDate endDate = period.endDate();

        boolean alreadyStarted =
                startDate == null
                        || !scanDate.isBefore(startDate);

        boolean notEnded =
                !scanDate.isAfter(endDate);

        return alreadyStarted && notEnded;
    }

    private java.util.Optional<DeadlineSoonCandidate> toCandidate(
            ApplicationPeriod period,
            LocalDate scanDate
    ) {
        long remainingDays = ChronoUnit.DAYS.between(
                scanDate,
                period.endDate()
        );

        return DeadlineThreshold
                .fromRemainingDays(remainingDays)
                .map(threshold ->
                        new DeadlineSoonCandidate(
                                scanDate,
                                period.endDate(),
                                threshold
                        )
                );
    }
}
