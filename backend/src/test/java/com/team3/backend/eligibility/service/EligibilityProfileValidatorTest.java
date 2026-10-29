package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.dto.ProfileAnswers;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Stream;

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

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidIncomes")
    void 소득의_상태와_금액_단위를_개인과_가구_모두_검증한다(
            String field, String reason, ProfileAnswers.Income income) {
        var request = incomeRequest(field, income);
        assertInvalid(request);
    }

    static Stream<Arguments> invalidIncomes() {
        return Stream.of("personalIncome", "householdIncome").flatMap(field -> Stream.of(
                Arguments.of(field, "상태 누락", new ProfileAnswers.Income(null, null, null)),
                Arguments.of(field, "해당없음 불허", new ProfileAnswers.Income(AnswerStatus.NOT_APPLICABLE, null, null)),
                Arguments.of(field, "금액 누락", new ProfileAnswers.Income(AnswerStatus.PROVIDED, null, IncomePeriod.MONTHLY)),
                Arguments.of(field, "단위 누락", new ProfileAnswers.Income(AnswerStatus.PROVIDED, BigDecimal.TEN, null)),
                Arguments.of(field, "음수", new ProfileAnswers.Income(AnswerStatus.PROVIDED, new BigDecimal("-1"), IncomePeriod.YEARLY)),
                Arguments.of(field, "원 미만 소수", new ProfileAnswers.Income(AnswerStatus.PROVIDED, new BigDecimal("0.1"), IncomePeriod.MONTHLY)),
                Arguments.of(field, "DB 금액 범위 초과", new ProfileAnswers.Income(AnswerStatus.PROVIDED, new BigDecimal("1000000000000000"), IncomePeriod.YEARLY)),
                Arguments.of(field, "모름에 금액 포함", new ProfileAnswers.Income(AnswerStatus.UNKNOWN, BigDecimal.TEN, null)),
                Arguments.of(field, "모름에 단위 포함", new ProfileAnswers.Income(AnswerStatus.UNKNOWN, null, IncomePeriod.MONTHLY))));
    }

    @ParameterizedTest(name = "{0} 금액 {1}")
    @CsvSource({"personalIncome, 0", "personalIncome, 999999999999999",
            "householdIncome, 0.00", "householdIncome, 999999999999999"})
    void 소득_범위의_양끝과_정수로_표현된_소수는_허용한다(String field, BigDecimal amount) {
        assertThatCode(() -> validator.validate(incomeRequest(field,
                new ProfileAnswers.Income(AnswerStatus.PROVIDED, amount, IncomePeriod.MONTHLY)), TODAY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOptionalAnswers")
    void 선택_응답의_누락과_범위를_각각_검증한다(String reason, EligibilityProfileUpdateRequest request) {
        assertInvalid(request);
    }

    static Stream<Arguments> invalidOptionalAnswers() {
        return Stream.of(
                Arguments.of("중위소득 상태 누락", base(null, new ProfileAnswers.MedianIncome(null, null), null, null, null)),
                Arguments.of("중위소득 값 누락", base(null, new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, null), null, null, null)),
                Arguments.of("모름에 중위소득 포함", base(null, new ProfileAnswers.MedianIncome(AnswerStatus.UNKNOWN, new BigDecimal("50")), null, null, null)),
                Arguments.of("중위소득 음수", base(null, new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, new BigDecimal("-0.01")), null, null, null)),
                Arguments.of("중위소득 상한 초과", base(null, new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, new BigDecimal("1000")), null, null, null)),
                Arguments.of("중위소득 소수 세 자리", base(null, new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, new BigDecimal("1.001")), null, null, null)),
                Arguments.of("학자금 상태 누락", base(null, null, new ProfileAnswers.StudentLoanBracket(null, null), null, null)),
                Arguments.of("학자금 값 누락", base(null, null, new ProfileAnswers.StudentLoanBracket(AnswerStatus.PROVIDED, null), null, null)),
                Arguments.of("학자금 하한 미달", base(null, null, new ProfileAnswers.StudentLoanBracket(AnswerStatus.PROVIDED, 0), null, null)),
                Arguments.of("학자금 상한 초과", base(null, null, new ProfileAnswers.StudentLoanBracket(AnswerStatus.PROVIDED, 11), null, null)),
                Arguments.of("해당없음에 학자금 포함", base(null, null, new ProfileAnswers.StudentLoanBracket(AnswerStatus.NOT_APPLICABLE, 3), null, null)),
                Arguments.of("특화대상 상태 누락", base(null, null, null, new ProfileAnswers.SpecialTargets(null, null), null)),
                Arguments.of("특화대상 값 누락", base(null, null, null, new ProfileAnswers.SpecialTargets(AnswerStatus.PROVIDED, null), null)),
                Arguments.of("특화대상 빈 목록", base(null, null, null, new ProfileAnswers.SpecialTargets(AnswerStatus.PROVIDED, Set.of()), null)),
                Arguments.of("해당없음에 특화대상 포함", base(null, null, null, new ProfileAnswers.SpecialTargets(AnswerStatus.NOT_APPLICABLE, Set.of(SpecialTarget.WOMAN)), null)),
                Arguments.of("면제에 복무개월 포함", base(null, null, null, null, new ProfileAnswers.MilitaryService(MilitaryServiceStatus.EXEMPTED, 10))),
                Arguments.of("병역 상태 누락", base(null, null, null, null, new ProfileAnswers.MilitaryService(null, null))),
                Arguments.of("음수 복무개월", base(null, null, null, null, new ProfileAnswers.MilitaryService(MilitaryServiceStatus.COMPLETED, -1))));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "999.99, 10"})
    void 중위소득과_학자금_구간_양끝을_허용한다(BigDecimal percent, int bracket) {
        assertThatCode(() -> validator.validate(base(null,
                new ProfileAnswers.MedianIncome(AnswerStatus.PROVIDED, percent),
                new ProfileAnswers.StudentLoanBracket(AnswerStatus.PROVIDED, bracket), null, null), TODAY))
                .doesNotThrowAnyException();
    }

    private void assertInvalid(EligibilityProfileUpdateRequest request) {
        assertThatThrownBy(() -> validator.validate(request, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EligibilityErrorCode.INVALID_PROFILE_VALUE);
    }

    private static EligibilityProfileUpdateRequest incomeRequest(String field, ProfileAnswers.Income income) {
        return new EligibilityProfileUpdateRequest(
                LocalDate.of(1998, 5, 15), "1168000000", EmploymentStatus.UNEMPLOYED, EducationLevel.UNIVERSITY,
                null, null, null, null, null,
                field.equals("personalIncome") ? income : null,
                field.equals("householdIncome") ? income : null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static EligibilityProfileUpdateRequest base(ProfileAnswers.Income personal,
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
