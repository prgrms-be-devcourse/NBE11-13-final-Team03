package com.team3.backend.eligibility.api.local;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpportunityInfoAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Test
    void 마감일은_오늘_이후로_끝나는_신청기간_중_가장_이른_종료일() {
        List<ApplicationWindowRow> windows = List.of(
                window(PeriodType.FIXED, "2026-09-01", "2026-09-30", ValidationStatus.VALID),
                window(PeriodType.FIXED, "2026-12-01", "2026-12-31", ValidationStatus.VALID),
                window(PeriodType.FIXED, "2026-10-01", "2026-10-16", ValidationStatus.VALID));

        assertThat(OpportunityInfoAssembler.nextDeadline(windows, TODAY)).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void 상시_마감_미확인_검증오류_기간은_마감일이_없다() {
        List<ApplicationWindowRow> windows = List.of(
                window(PeriodType.ALWAYS, null, null, ValidationStatus.VALID),
                window(PeriodType.FIXED, "2026-09-01", "2026-09-30", ValidationStatus.VALID),
                window(PeriodType.UNKNOWN, null, null, ValidationStatus.UNKNOWN),
                window(PeriodType.FIXED, "2026-10-01", "2026-12-31", ValidationStatus.INVALID));

        assertThat(OpportunityInfoAssembler.nextDeadline(windows, TODAY)).isNull();
    }

    private static ApplicationWindowRow window(PeriodType type, String start, String end, ValidationStatus status) {
        return new ApplicationWindowRow(1L, type, start == null ? null : LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end), status, null);
    }
}
