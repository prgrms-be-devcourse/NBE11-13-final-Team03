package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.backend.tracking.domain.model.ApplicationStatusTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.team3.backend.tracking.domain.entity.OpportunityEventType.APPLICATION_CLOSED;
import static com.team3.backend.tracking.domain.entity.OpportunityEventType.APPLICATION_OPENED;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStatusTransitionDetectorTest {

    private static final LocalDate TODAY =
            LocalDate.of(2026, 10, 20);

    private ApplicationStatusTransitionDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ApplicationStatusTransitionDetector(
                new ApplicationStatusCalculator()
        );
    }

    @Test
    void 오늘부터_신청이_시작되면_모집_시작_이벤트를_반환한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY,
                TODAY.plusDays(10)
        );

        Optional<ApplicationStatusTransition> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isPresent();

        ApplicationStatusTransition transition = result.orElseThrow();

        assertThat(transition.eventDate()).isEqualTo(TODAY);
        assertThat(transition.previousStatus())
                .isEqualTo(ApplicationStatus.UPCOMING);
        assertThat(transition.currentStatus())
                .isEqualTo(ApplicationStatus.OPEN);
        assertThat(transition.eventType())
                .isEqualTo(APPLICATION_OPENED);
    }

    @Test
    void 어제까지_신청할_수_있었다면_오늘_모집_마감_이벤트를_반환한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.minusDays(1)
        );

        Optional<ApplicationStatusTransition> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isPresent();

        ApplicationStatusTransition transition = result.orElseThrow();

        assertThat(transition.previousStatus())
                .isEqualTo(ApplicationStatus.OPEN);
        assertThat(transition.currentStatus())
                .isEqualTo(ApplicationStatus.CLOSED);
        assertThat(transition.eventType())
                .isEqualTo(APPLICATION_CLOSED);
    }

    @Test
    void 모집_상태가_변하지_않으면_이벤트를_반환하지_않는다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(1),
                TODAY.plusDays(10)
        );

        Optional<ApplicationStatusTransition> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 상시_모집은_시작이나_마감_이벤트를_반환하지_않는다() {
        ApplicationPeriod period = new ApplicationPeriod(
                PeriodType.ALWAYS,
                null,
                null,
                ValidationStatus.VALID,
                "상시 모집"
        );

        Optional<ApplicationStatusTransition> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 검증되지_않은_신청기간은_이벤트를_반환하지_않는다() {
        ApplicationPeriod period = new ApplicationPeriod(
                PeriodType.FIXED,
                TODAY,
                TODAY.plusDays(10),
                ValidationStatus.INVALID,
                "2026-10-20 ~ 2026-10-30"
        );

        Optional<ApplicationStatusTransition> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 한_신청기간이_끝나도_다른_기간이_열려_있으면_마감으로_판정하지_않는다() {
        ApplicationPeriod endingPeriod = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.minusDays(1)
        );

        ApplicationPeriod openingPeriod = fixedPeriod(
                TODAY,
                TODAY.plusDays(10)
        );

        Optional<ApplicationStatusTransition> result =
                detector.detect(
                        List.of(endingPeriod, openingPeriod),
                        TODAY
                );

        assertThat(result).isEmpty();
    }

    private ApplicationPeriod fixedPeriod(
            LocalDate startDate,
            LocalDate endDate
    ) {
        return new ApplicationPeriod(
                PeriodType.FIXED,
                startDate,
                endDate,
                ValidationStatus.VALID,
                startDate + " ~ " + endDate
        );
    }
}
