package com.team3.backend.tracking.service;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.tracking.domain.model.DeadlineSoonCandidate;
import com.team3.backend.tracking.domain.model.DeadlineThreshold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineSoonDetectorTest {

    private static final LocalDate TODAY =
            LocalDate.of(2026, 10, 20);

    private DeadlineSoonDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DeadlineSoonDetector(
                new ApplicationStatusCalculator()
        );
    }

    @Test
    void 마감일까지_7일_남으면_D7_후보를_반환한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.plusDays(7)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).containsExactly(
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(7),
                        DeadlineThreshold.D_7
                )
        );
    }

    @Test
    void 마감일까지_3일_남으면_D3_후보를_반환한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.plusDays(3)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).containsExactly(
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(3),
                        DeadlineThreshold.D_3
                )
        );
    }

    @Test
    void 마감일까지_1일_남으면_D1_후보를_반환한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.plusDays(1)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).containsExactly(
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(1),
                        DeadlineThreshold.D_1
                )
        );
    }

    @Test
    void 정해진_임계값이_아니면_후보를_반환하지_않는다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.plusDays(5)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 아직_모집이_시작되지_않은_기간은_제외한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.plusDays(1),
                TODAY.plusDays(7)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 이미_마감된_기간은_제외한다() {
        ApplicationPeriod period = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.minusDays(1)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(period), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 상시_모집_기간이_있으면_마감_임박_후보를_반환하지_않는다() {
        ApplicationPeriod always = new ApplicationPeriod(
                PeriodType.ALWAYS,
                null,
                null,
                ValidationStatus.VALID,
                "상시 모집"
        );

        ApplicationPeriod fixed = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.plusDays(7)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(
                        List.of(always, fixed),
                        TODAY
                );

        assertThat(result).isEmpty();
    }

    @Test
    void 검증되지_않은_신청기간은_제외한다() {
        ApplicationPeriod invalid = new ApplicationPeriod(
                PeriodType.FIXED,
                TODAY.minusDays(10),
                TODAY.plusDays(7),
                ValidationStatus.INVALID,
                "잘못된 신청기간"
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(List.of(invalid), TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void 같은_마감일이_중복되어도_후보는_한_번만_반환한다() {
        ApplicationPeriod first = fixedPeriod(
                TODAY.minusDays(10),
                TODAY.plusDays(7)
        );

        ApplicationPeriod second = fixedPeriod(
                TODAY.minusDays(5),
                TODAY.plusDays(7)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(
                        List.of(first, second),
                        TODAY
                );

        assertThat(result).containsExactly(
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(7),
                        DeadlineThreshold.D_7
                )
        );
    }

    @Test
    void 여러_신청기간에서_현재_열린_기간만_판정한다() {
        ApplicationPeriod upcoming = fixedPeriod(
                TODAY.plusDays(1),
                TODAY.plusDays(7)
        );

        ApplicationPeriod open = fixedPeriod(
                TODAY.minusDays(5),
                TODAY.plusDays(3)
        );

        List<DeadlineSoonCandidate> result =
                detector.detect(
                        List.of(upcoming, open),
                        TODAY
                );

        assertThat(result).containsExactly(
                new DeadlineSoonCandidate(
                        TODAY,
                        TODAY.plusDays(3),
                        DeadlineThreshold.D_3
                )
        );
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
