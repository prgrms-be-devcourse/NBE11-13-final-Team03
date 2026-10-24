package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.tracking.domain.model.DeadlineExtensionAssessment;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;

import static com.team3.backend.tracking.domain.model.DeadlineExtensionAssessment.Outcome.*;

/**
 * Pure comparison of application windows, never business/training dates.
 * The caller must suppress normalization-rule changes and verify version ownership before calling.
 * No clock filtering: historical change detection and current notification relevance are separate.
 */
@Component
public class DeadlineExtensionDetector {

    public DeadlineExtensionAssessment detect(Collection<ApplicationPeriod> before,
                                               Collection<ApplicationPeriod> after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        // Do not filter first: ignoring another invalid/always/unknown window would hide ambiguity.
        if (before.size() != 1 || after.size() != 1) return inconclusive();
        var previous = before.iterator().next();
        var current = after.iterator().next();
        if (!isComparable(previous) || !isComparable(current)
                || !previous.startDate().equals(current.startDate())) {
            return inconclusive();
        }
        return new DeadlineExtensionAssessment(
                current.endDate().isAfter(previous.endDate()) ? EXTENDED : NOT_EXTENDED,
                previous.endDate(), current.endDate());
    }

    private boolean isComparable(ApplicationPeriod period) {
        return period != null && period.validationStatus() == ValidationStatus.VALID
                && period.periodType() == PeriodType.FIXED
                && period.startDate() != null && period.endDate() != null
                && !period.startDate().isAfter(period.endDate());
    }

    private DeadlineExtensionAssessment inconclusive() {
        return new DeadlineExtensionAssessment(INCONCLUSIVE, null, null);
    }
}
