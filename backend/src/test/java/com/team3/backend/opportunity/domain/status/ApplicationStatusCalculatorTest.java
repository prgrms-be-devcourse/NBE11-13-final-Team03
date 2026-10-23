package com.team3.backend.opportunity.domain.status;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 신청기간 → 기준일 신청 상태 계산. 신청기간 원문 해석(정규화)은 data-service에서 검증한다. 기준일은 2026-10-09 */
class ApplicationStatusCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private final ApplicationStatusCalculator calculator = new ApplicationStatusCalculator();

    @Test
    void 고정기간은_기준일로_상태를_계산한다() {
        assertThat(status(fixed("2026-10-01", "2026-10-31"))).isEqualTo(ApplicationStatus.OPEN);
        assertThat(status(fixed("2026-11-01", "2026-11-30"))).isEqualTo(ApplicationStatus.UPCOMING);
        assertThat(status(fixed("2026-08-01", "2026-08-31"))).isEqualTo(ApplicationStatus.CLOSED);
    }

    @Test
    void 여러_기간은_열린_기간_예정_기간_종료_순서로_본다() {
        List<ApplicationPeriod> periods = List.of(fixed("2026-10-01", "2026-10-31"), fixed("2026-12-01", "2026-12-31"));

        assertThat(calculator.calculate(periods, LocalDate.of(2026, 11, 15))).isEqualTo(ApplicationStatus.UPCOMING);
        assertThat(calculator.calculate(periods, LocalDate.of(2026, 12, 5))).isEqualTo(ApplicationStatus.OPEN);
        assertThat(calculator.calculate(periods, LocalDate.of(2027, 1, 5))).isEqualTo(ApplicationStatus.CLOSED);
    }

    @Test
    void VALID가_아닌_기간은_계산에서_빠진다() {
        ApplicationPeriod invalid = new ApplicationPeriod(PeriodType.FIXED, null, null, ValidationStatus.INVALID, "20260601 ~ 20250710");

        assertThat(status(invalid)).isEqualTo(ApplicationStatus.UNKNOWN);
        assertThat(status(new ApplicationPeriod(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN, null)))
                .isEqualTo(ApplicationStatus.UNKNOWN);
        assertThat(calculator.calculate(List.of(invalid, fixed("2026-10-01", "2026-10-31")), TODAY))
                .isEqualTo(ApplicationStatus.OPEN);
    }

    @Test
    void 상시와_마감() {
        assertThat(status(new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, null)))
                .isEqualTo(ApplicationStatus.ALWAYS_OPEN);
        assertThat(status(new ApplicationPeriod(PeriodType.CLOSED, null, null, ValidationStatus.VALID, null)))
                .isEqualTo(ApplicationStatus.CLOSED);
        assertThat(calculator.calculate(List.of(new ApplicationPeriod(PeriodType.ALWAYS, null, null, ValidationStatus.VALID, null),
                fixed("2026-10-01", "2026-10-31")), TODAY)).isEqualTo(ApplicationStatus.OPEN);
    }

    @Test
    void 한쪽_날짜만_있는_기간() {
        assertThat(status(new ApplicationPeriod(PeriodType.FIXED, null, LocalDate.of(2026, 12, 31), ValidationStatus.VALID, null)))
                .isEqualTo(ApplicationStatus.OPEN);
        assertThat(status(new ApplicationPeriod(PeriodType.FIXED, LocalDate.of(2026, 11, 1), null, ValidationStatus.VALID, null)))
                .isEqualTo(ApplicationStatus.UPCOMING);
    }

    private ApplicationStatus status(ApplicationPeriod period) {
        return calculator.calculate(List.of(period), TODAY);
    }

    private static ApplicationPeriod fixed(String start, String end) {
        return new ApplicationPeriod(PeriodType.FIXED, LocalDate.parse(start), LocalDate.parse(end), ValidationStatus.VALID,
                start + " ~ " + end);
    }
}
