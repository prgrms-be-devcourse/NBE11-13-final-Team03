package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.dto.ProfileAnswers;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EligibilityProfileValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private final EligibilityProfileValidator validator = new EligibilityProfileValidator();

    @Test
    void 필수값만_있어도_통과한다() {
        assertThatCode(() -> validator.validate(base(null, null, null, null, null), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void 상태와_값이_맞으면_통과한다() {
        var request = base(
                new ProfileAnswers.Income(AnswerStatus.PROVIDED, BigDecimal.ZERO, IncomePeriod.MONTHLY),
                new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, new BigDecimal("150.5")),
                new ProfileAnswers.StudentLoanBracket(AnswerStatus.UNKNOWN, null),
                new ProfileAnswers.SpecialTargets(AnswerStatus.PROVIDED, Set.of(SpecialTarget.WOMAN)),
                new ProfileAnswers.MilitaryService(MilitaryServiceStatus.COMPLETED, 18));
        assertThatCode(() -> validator.validate(request, TODAY)).doesNotThrowAnyException();
    }

    @Test
    void 필수값이_없으면_실패() {
        var request = new EligibilityProfileUpdateRequest(
                LocalDate.of(1998, 5, 15), " ", EmploymentStatus.UNEMPLOYED, EducationLevel.UNIVERSITY,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertInvalid(request);
    }

    @Test
    void 미래_생년월일은_실패() {
        var request = new EligibilityProfileUpdateRequest(
                TODAY.plusDays(1), "1168000000", EmploymentStatus.UNEMPLOYED, EducationLevel.UNIVERSITY,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertInvalid(request);
    }

    @Test
    void 소득_해당없음_음수_단위누락은_실패() {
        assertInvalid(base(new ProfileAnswers.Income(AnswerStatus.NOT_APPLICABLE, null, null), null, null, null, null));
        assertInvalid(base(new ProfileAnswers.Income(AnswerStatus.PROVIDED, new BigDecimal("-1"), IncomePeriod.YEARLY), null, null, null, null));
        assertInvalid(base(new ProfileAnswers.Income(AnswerStatus.PROVIDED, new BigDecimal("100"), null), null, null, null, null));
        assertInvalid(base(new ProfileAnswers.Income(AnswerStatus.UNKNOWN, new BigDecimal("100"), null), null, null, null, null));
    }

    @Test
    void 중위소득_학자금구간_범위와_짝을_검사한다() {
        assertInvalid(base(null, new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, null), null, null, null));
        assertInvalid(base(null, new ProfileAnswers.MedianIncome(AnswerStatus.UNKNOWN, new BigDecimal("50")), null, null, null));
        assertInvalid(base(null, new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, new BigDecimal("1000")), null, null, null));
        assertInvalid(base(null, null, new ProfileAnswers.StudentLoanBracket(AnswerStatus.PROVIDED, 11), null, null));
        assertInvalid(base(null, null, new ProfileAnswers.StudentLoanBracket(AnswerStatus.NOT_APPLICABLE, 3), null, null));
    }

    @Test
    void 특화대상_병역의_짝을_검사한다() {
        assertInvalid(base(null, null, null, new ProfileAnswers.SpecialTargets(AnswerStatus.PROVIDED, Set.of()), null));
        assertInvalid(base(null, null, null, new ProfileAnswers.SpecialTargets(AnswerStatus.NOT_APPLICABLE, Set.of(SpecialTarget.WOMAN)), null));
        assertInvalid(base(null, null, null, null, new ProfileAnswers.MilitaryService(MilitaryServiceStatus.EXEMPTED, 10)));
        assertInvalid(base(null, null, null, null, new ProfileAnswers.MilitaryService(null, null)));
    }

    private void assertInvalid(EligibilityProfileUpdateRequest request) {
        assertThatThrownBy(() -> validator.validate(request, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(EligibilityErrorCode.INVALID_PROFILE_VALUE);
    }

    private EligibilityProfileUpdateRequest base(ProfileAnswers.Income personal,
                                                 ProfileAnswers.MedianIncome median,
                                                 ProfileAnswers.StudentLoanBracket bracket,
                                                 ProfileAnswers.SpecialTargets specialTargets,
                                                 ProfileAnswers.MilitaryService military) {
        return new EligibilityProfileUpdateRequest(
                LocalDate.of(1998, 5, 15), "1168000000",
                EmploymentStatus.UNEMPLOYED, EducationLevel.UNIVERSITY, null,
                null, null, null, null,
                personal, null, median, bracket, null, null, null,
                specialTargets, military, null, null, null);
    }
}
