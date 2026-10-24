package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.tracking.domain.model.DeadlineExtensionAssessment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static com.team3.backend.tracking.domain.model.DeadlineExtensionAssessment.Outcome.*;
import static org.assertj.core.api.Assertions.*;

class DeadlineExtensionDetectorTest {
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 25);
    private final DeadlineExtensionDetector detector = new DeadlineExtensionDetector();

    @Test
    void laterDeadlineOfOneMatchedApplicationWindowIsAnExtension() {
        var result = detector.detect(List.of(fixed(START, END)), List.of(fixed(START, END.plusDays(7))));
        assertThat(result).isEqualTo(new DeadlineExtensionAssessment(EXTENDED, END, END.plusDays(7)));
    }

    @Test
    void unchangedDeadlineIsNotAnExtensionEvenWhenTextChanges() {
        var current = new ApplicationPeriod(PeriodType.FIXED, START, END, ValidationStatus.VALID, "표현만 변경");
        assertThat(detector.detect(List.of(fixed(START, END)), List.of(current)))
                .isEqualTo(new DeadlineExtensionAssessment(NOT_EXTENDED, END, END));
    }

    @Test
    void earlierDeadlineIsNotAnExtension() {
        assertThat(detector.detect(List.of(fixed(START, END)), List.of(fixed(START, END.minusDays(1)))))
                .isEqualTo(new DeadlineExtensionAssessment(NOT_EXTENDED, END, END.minusDays(1)));
    }

    @Test
    void changedStartCannotBeAssumedToRepresentTheSameRound() {
        assertInconclusive(List.of(fixed(START, END)), List.of(fixed(START.plusDays(1), END.plusDays(7))));
        assertInconclusive(List.of(fixed(START, END)), List.of(fixed(END.plusDays(1), END.plusDays(7))));
    }

    @Test
    void absentWindowsAreNotInferredFromOtherDates() {
        assertInconclusive(List.of(), List.of(fixed(START, END)));
        assertInconclusive(List.of(fixed(START, END)), List.of());
        assertInconclusive(List.of(), List.of());
    }

    @Test
    void multipleWindowsAreNotMatchedByOrderOrMaximumDeadline() {
        var windows = List.of(fixed(START, END), fixed(START.plusDays(1), END.plusDays(1)));
        assertInconclusive(windows, List.of(fixed(START, END.plusDays(7))));
        assertInconclusive(List.of(fixed(START, END)), windows);
        assertInconclusive(windows, windows.reversed());
    }

    @Test
    void invalidOrUnverifiedWindowsRemainInconclusiveOnEitherSide() {
        for (var status : ValidationStatus.values()) {
            if (status == ValidationStatus.VALID) continue;
            var uncertain = new ApplicationPeriod(PeriodType.FIXED, START, END.plusDays(7), status, "검증 전");
            assertInconclusive(List.of(fixed(START, END)), List.of(uncertain));
            assertInconclusive(List.of(uncertain), List.of(fixed(START, END.plusDays(10))));
        }
    }

    @Test
    void alwaysClosedAndUnknownPeriodsAreNotTurnedIntoFixedDeadlines() {
        for (var type : List.of(PeriodType.ALWAYS, PeriodType.CLOSED, PeriodType.UNKNOWN)) {
            var other = new ApplicationPeriod(type, START, END, ValidationStatus.VALID, "원문");
            assertInconclusive(List.of(other), List.of(fixed(START, END.plusDays(7))));
            assertInconclusive(List.of(fixed(START, END)), List.of(other));
        }
    }

    @Test
    void missingStartOrEndDoesNotProvideEnoughEvidenceForMatching() {
        for (var incomplete : List.of(fixed(null, END), fixed(START, null), fixed(null, null))) {
            assertInconclusive(List.of(incomplete), List.of(fixed(START, END.plusDays(7))));
            assertInconclusive(List.of(fixed(START, END)), List.of(incomplete));
        }
        assertInconclusive(List.of(fixed(null, END)), List.of(fixed(null, END.plusDays(7))));
    }

    @Test
    void reversedDatesAreRejectedEvenIfIncorrectlyMarkedValid() {
        var malformed = fixed(END.plusDays(1), END);
        assertInconclusive(List.of(malformed), List.of(fixed(END.plusDays(1), END.plusDays(7))));
        assertInconclusive(List.of(fixed(START, END)), List.of(fixed(START, START.minusDays(1))));
    }

    @Test
    void doesNotDiscardAdditionalUncertainOrAlwaysWindowsToForceAMatch() {
        var unknown = new ApplicationPeriod(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN, "미상");
        var always = new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, "상시");
        assertInconclusive(List.of(fixed(START, END), unknown), List.of(fixed(START, END.plusDays(7))));
        assertInconclusive(List.of(fixed(START, END)), List.of(fixed(START, END.plusDays(7)), always));
    }

    @Test
    void nullCollectionsFailButNullWindowIsInconclusive() {
        assertThatThrownBy(() -> detector.detect(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> detector.detect(List.of(), null)).isInstanceOf(NullPointerException.class);
        assertInconclusive(Collections.singletonList(null), List.of(fixed(START, END)));
        assertInconclusive(List.of(fixed(START, END)), Collections.singletonList(null));
    }

    @Test
    void startEqualsEndIsAValidSingleDayWindowAndInputsAreUnchanged() {
        var before = new ArrayList<>(List.of(fixed(END, END)));
        var after = new ArrayList<>(List.of(fixed(END, END.plusDays(1))));
        var originalBefore = List.copyOf(before);
        var originalAfter = List.copyOf(after);
        assertThat(detector.detect(before, after).outcome()).isEqualTo(EXTENDED);
        assertThat(before).isEqualTo(originalBefore);
        assertThat(after).isEqualTo(originalAfter);
    }

    @Test
    void resultCannotClaimAnExtensionWithoutSupportingDeadlines() {
        assertThatThrownBy(() -> new DeadlineExtensionAssessment(EXTENDED, END, END))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlineExtensionAssessment(NOT_EXTENDED, END, END.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadlineExtensionAssessment(INCONCLUSIVE, END, END.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInconclusive(Collection<ApplicationPeriod> before, Collection<ApplicationPeriod> after) {
        assertThat(detector.detect(before, after)).isEqualTo(new DeadlineExtensionAssessment(INCONCLUSIVE, null, null));
    }

    private ApplicationPeriod fixed(LocalDate start, LocalDate end) {
        return new ApplicationPeriod(PeriodType.FIXED, start, end, ValidationStatus.VALID, "신청기간");
    }
}
