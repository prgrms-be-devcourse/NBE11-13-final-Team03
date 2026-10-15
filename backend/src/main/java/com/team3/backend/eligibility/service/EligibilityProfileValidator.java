package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.AnswerStatus;
import com.team3.backend.eligibility.domain.entity.MilitaryServiceStatus;
import com.team3.backend.eligibility.dto.EligibilityProfileUpdateRequest;
import com.team3.backend.eligibility.dto.ProfileAnswers;
import com.team3.backend.global.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.team3.backend.eligibility.exception.EligibilityErrorCode.INVALID_PROFILE_VALUE;

/**
 * 판정 조건 입력값 형식 검증 (지역 코드 존재 여부는 서비스에서 확인).
 * 필수값, 값 범위, 응답 상태와 값의 짝(PROVIDED일 때만 값이 있어야 함)을 본다.
 * 실패 사유는 BusinessException의 메시지(로그용)에 담는다.
 */
@Component
public class EligibilityProfileValidator {

    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    /** median_income_percent 컬럼 precision 5, scale 2 */
    private static final BigDecimal MAX_MEDIAN_PERCENT = new BigDecimal("999.99");
    /** income amount 컬럼 precision 15, scale 0 */
    private static final BigDecimal MAX_INCOME_AMOUNT = new BigDecimal("999999999999999");
    private static final int MAX_HOUSEHOLD_SIZE = 99;

    public void validate(EligibilityProfileUpdateRequest r, LocalDate today) {
        require(r.birthDate() != null, "birthDate는 필수입니다.");
        require(blankToNull(r.residenceRegionCode()) != null, "residenceRegionCode는 필수입니다.");
        require(r.employmentStatus() != null, "employmentStatus는 필수입니다.");
        require(r.educationLevel() != null, "educationLevel는 필수입니다.");

        require(!r.birthDate().isAfter(today), "birthDate는 오늘 이후일 수 없습니다.");
        require(!r.birthDate().isBefore(MIN_BIRTH_DATE), "birthDate가 너무 이릅니다.");

        validateIncome("personalIncome", r.personalIncome());
        validateIncome("householdIncome", r.householdIncome());
        validateMedianIncome(r.medianIncome());
        validateStudentLoanBracket(r.studentLoanBracket());
        if (r.householdSize() != null) {
            require(r.householdSize() >= 1 && r.householdSize() <= MAX_HOUSEHOLD_SIZE,
                    "householdSize는 1~" + MAX_HOUSEHOLD_SIZE + " 사이여야 합니다.");
        }
        validateSpecialTargets(r.specialTargets());
        validateMilitaryService(r.militaryService());
    }

    private void validateIncome(String field, ProfileAnswers.Income income) {
        if (income == null) {
            return;
        }
        require(income.status() != null, field + ".status는 필수입니다.");
        switch (income.status()) {
            case PROVIDED -> {
                require(income.amount() != null && income.period() != null,
                        field + "는 PROVIDED일 때 amount와 period가 필요합니다.");
                require(income.amount().signum() >= 0, field + ".amount는 음수일 수 없습니다.");
                require(income.amount().stripTrailingZeros().scale() <= 0, field + ".amount는 원 단위 정수여야 합니다.");
                require(income.amount().compareTo(MAX_INCOME_AMOUNT) <= 0, field + ".amount가 너무 큽니다.");
            }
            case UNKNOWN -> require(income.amount() == null && income.period() == null,
                    field + "는 UNKNOWN일 때 amount와 period를 보낼 수 없습니다.");
            case NOT_APPLICABLE -> fail(field + "에는 NOT_APPLICABLE을 쓸 수 없습니다. 소득이 없으면 PROVIDED + 0을 입력하세요.");
        }
    }

    private void validateMedianIncome(ProfileAnswers.MedianIncome median) {
        if (median == null) {
            return;
        }
        require(median.status() != null, "medianIncome.status는 필수입니다.");
        if (median.status() == AnswerStatus.PROVIDED) {
            BigDecimal percent = median.percent();
            require(percent != null, "medianIncome은 PROVIDED일 때 percent가 필요합니다.");
            require(percent.signum() >= 0 && percent.compareTo(MAX_MEDIAN_PERCENT) <= 0,
                    "medianIncome.percent는 0~" + MAX_MEDIAN_PERCENT + " 사이여야 합니다.");
            require(percent.stripTrailingZeros().scale() <= 2, "medianIncome.percent는 소수 둘째 자리까지입니다.");
        } else {
            require(median.percent() == null, "medianIncome은 PROVIDED가 아니면 percent를 보낼 수 없습니다.");
        }
    }

    private void validateStudentLoanBracket(ProfileAnswers.StudentLoanBracket bracket) {
        if (bracket == null) {
            return;
        }
        require(bracket.status() != null, "studentLoanBracket.status는 필수입니다.");
        if (bracket.status() == AnswerStatus.PROVIDED) {
            require(bracket.bracket() != null && bracket.bracket() >= 1 && bracket.bracket() <= 10,
                    "studentLoanBracket.bracket은 1~10 사이여야 합니다.");
        } else {
            require(bracket.bracket() == null, "studentLoanBracket은 PROVIDED가 아니면 bracket을 보낼 수 없습니다.");
        }
    }

    private void validateSpecialTargets(ProfileAnswers.SpecialTargets targets) {
        if (targets == null) {
            return;
        }
        require(targets.status() != null, "specialTargets.status는 필수입니다.");
        if (targets.status() == AnswerStatus.PROVIDED) {
            require(targets.targets() != null && !targets.targets().isEmpty(),
                    "specialTargets는 PROVIDED일 때 targets가 1개 이상 필요합니다. 해당 없으면 NOT_APPLICABLE을 쓰세요.");
            require(targets.targets().stream().noneMatch(java.util.Objects::isNull), "specialTargets.targets에 null이 있습니다.");
        } else {
            require(targets.targets() == null || targets.targets().isEmpty(),
                    "specialTargets는 PROVIDED가 아니면 targets를 보낼 수 없습니다.");
        }
    }

    private void validateMilitaryService(ProfileAnswers.MilitaryService military) {
        if (military == null) {
            return;
        }
        require(military.status() != null, "militaryService.status는 필수입니다.");
        if (military.months() == null) {
            return;
        }
        require(military.status() == MilitaryServiceStatus.COMPLETED || military.status() == MilitaryServiceStatus.SERVING,
                "militaryService.months는 COMPLETED·SERVING일 때만 보낼 수 있습니다.");
        require(military.months() >= 0, "militaryService.months는 음수일 수 없습니다.");
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            fail(reason);
        }
    }

    private static void fail(String reason) {
        throw new BusinessException(INVALID_PROFILE_VALUE, reason);
    }
}
