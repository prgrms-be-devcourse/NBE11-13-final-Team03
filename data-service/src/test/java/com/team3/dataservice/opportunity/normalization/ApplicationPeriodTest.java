package com.team3.dataservice.opportunity.normalization;

import com.team3.dataservice.opportunity.domain.status.ApplicationPeriod;
import com.team3.dataservice.opportunity.domain.status.ApplicationStatus;
import com.team3.dataservice.opportunity.domain.status.ApplicationStatusCalculator;
import com.team3.dataservice.opportunity.domain.entity.PeriodType;
import com.team3.dataservice.opportunity.domain.entity.ValidationStatus;
import com.team3.dataservice.opportunity.normalization.dto.YouthPolicyRaw;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 신청기간 정규화 + 기준일 상태 계산. 기준일은 정제 문서와 같은 2026-10-09 */
class ApplicationPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private final ApplicationPeriodNormalizer normalizer = new ApplicationPeriodNormalizer();
    private final ApplicationStatusCalculator calculator = new ApplicationStatusCalculator();

    @Test
    void 고정기간을_파싱하고_기준일로_상태를_계산한다() {
        assertThat(status("0057001", "20261001 ~ 20261031")).isEqualTo(ApplicationStatus.OPEN);
        assertThat(status("0057001", "20261101 ~ 20261130")).isEqualTo(ApplicationStatus.UPCOMING);
        assertThat(status("0057001", "20260801 ~ 20260831")).isEqualTo(ApplicationStatus.CLOSED);
    }

    @Test
    void 정규화_결과에는_기준일에_따른_값이_없다() {
        List<ApplicationPeriod> periods = periods("0057001", "20261001 ~ 20261031");

        assertThat(periods).containsExactly(new ApplicationPeriod(PeriodType.FIXED,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), ValidationStatus.VALID, "20261001 ~ 20261031"));
    }

    @Test
    void 여러_기간을_각각_나눈다() {
        List<ApplicationPeriod> periods = periods("0057001", "20261001 ~ 20261031\n20261201 ~ 20261231");

        assertThat(periods).hasSize(2);
        assertThat(calculator.calculate(periods, LocalDate.of(2026, 11, 15))).isEqualTo(ApplicationStatus.UPCOMING);
        assertThat(calculator.calculate(periods, LocalDate.of(2026, 12, 5))).isEqualTo(ApplicationStatus.OPEN);
        assertThat(calculator.calculate(periods, LocalDate.of(2027, 1, 5))).isEqualTo(ApplicationStatus.CLOSED);
    }

    @Test
    void 다양한_날짜_표기를_읽는다() {
        assertThat(periods("0057001", "2026.10.01.(수) ~ 2026.10.31.(금)").get(0).startDate())
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(periods("0057001", "2026-3-18 ~ 2026-4-3").get(0).endDate())
                .isEqualTo(LocalDate.of(2026, 4, 3));
    }

    @Test
    void 시작일이_종료일보다_늦거나_없는_날짜면_오류() {
        assertThat(periods("0057001", "20260601 ~ 20250710").get(0).validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(periods("0057001", "20260231 ~ 20260301").get(0).validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(status("0057001", "20260601 ~ 20250710")).isEqualTo(ApplicationStatus.UNKNOWN);
    }

    @Test
    void 고정기간_코드인데_날짜를_못_찾으면_임의로_채우지_않는다() {
        ApplicationPeriod period = periods("0057001", "추후 공지").get(0);

        assertThat(period.validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(period.startDate()).isNull();
        assertThat(period.periodText()).isEqualTo("추후 공지");
    }

    @Test
    void 코드별_처리() {
        assertThat(status("0057002", "")).isEqualTo(ApplicationStatus.ALWAYS_OPEN);
        assertThat(status("0057003", "")).isEqualTo(ApplicationStatus.CLOSED);
        assertThat(status("", "20261001 ~ 20261031")).isEqualTo(ApplicationStatus.UNKNOWN);
        assertThat(periods("9999999", "").get(0).validationStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
    }

    @Test
    void 한쪽_날짜만_있는_기간() {
        assertThat(status("0057001", "~ 20261231")).isEqualTo(ApplicationStatus.OPEN);
        assertThat(status("0057001", "20261101 ~")).isEqualTo(ApplicationStatus.UPCOMING);
    }

    private List<ApplicationPeriod> periods(String code, String ymd) {
        return normalizer.normalize(new YouthPolicyRaw("P1", "정책", null, null, null, null, null, code, ymd));
    }

    private ApplicationStatus status(String code, String ymd) {
        return calculator.calculate(periods(code, ymd), TODAY);
    }
}
