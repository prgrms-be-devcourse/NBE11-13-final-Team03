package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

/**
 * PUT /api/users/me/eligibility-profile 요청 (전체 교체).
 * 필수: birthDate, residenceRegionCode, employmentStatus, educationLevel. 그 외 항목이 없으면 미입력으로 저장된다.
 * 지역 코드는 시·군·구 코드이며 regions 사전에 있어야 한다.
 * 필수값·범위는 Bean Validation(@Valid)으로, 응답 상태와 값의 짝처럼 조건에 따라 달라지는 검증은 EligibilityProfileValidator가 맡는다.
 */
public record EligibilityProfileUpdateRequest(
        @NotNull(message = "생년월일은 필수입니다.")
        @PastOrPresent(message = "생년월일은 오늘 이후일 수 없습니다.")
        LocalDate birthDate,
        @NotBlank(message = "거주지역 코드는 필수입니다.")
        String residenceRegionCode,
        @NotNull(message = "고용 상태는 필수입니다.")
        EmploymentStatus employmentStatus,
        @NotNull(message = "최종 학력은 필수입니다.")
        EducationLevel educationLevel,
        EnrollmentStatus enrollmentStatus,
        String schoolRegionCode,
        MaritalStatus maritalStatus,
        HousingStatus housingStatus,
        YesNoUnknown homelessStatus,
        @Valid ProfileAnswers.Income personalIncome,
        @Valid ProfileAnswers.Income householdIncome,
        @Valid ProfileAnswers.MedianIncome medianIncome,
        @Valid ProfileAnswers.StudentLoanBracket studentLoanBracket,
        @Min(value = 1, message = "가구원 수는 1~99명입니다.") @Max(value = 99, message = "가구원 수는 1~99명입니다.")
        Integer householdSize,
        YesNoUnknown householderStatus,
        MajorField majorField,
        @Valid ProfileAnswers.SpecialTargets specialTargets,
        @Valid ProfileAnswers.MilitaryService militaryService,
        TrainingCardStatus trainingCardStatus,
        TrainingParticipation kdtParticipation,
        TrainingParticipation otherTrainingParticipation
) {

    /**
     * 지역 코드는 서비스에서 Region으로 조회해 넘긴다. 형식 검증(필수값, 범위, 상태와 값의 짝)은 서비스에서 수행한다.
     */
    public EligibilityProfile toEntity(Long userId, Region residenceRegion, Region schoolRegion) {
        return EligibilityProfile.builder()
                .userId(userId)
                .birthDate(birthDate)
                .residenceRegion(residenceRegion)
                .employmentStatus(employmentStatus)
                .educationLevel(educationLevel)
                .enrollmentStatus(enrollmentStatus)
                .schoolRegion(schoolRegion)
                .maritalStatus(maritalStatus)
                .housingStatus(housingStatus)
                .homelessStatus(homelessStatus)
                .personalIncome(personalIncome == null ? null : personalIncome.toEntity())
                .householdIncome(householdIncome == null ? null : householdIncome.toEntity())
                .medianIncomeStatus(medianIncome == null ? null : medianIncome.status())
                .medianIncomePercent(medianIncome == null ? null : medianIncome.percent())
                .studentLoanBracketStatus(studentLoanBracket == null ? null : studentLoanBracket.status())
                .studentLoanBracket(studentLoanBracket == null ? null : studentLoanBracket.bracket())
                .householdSize(householdSize)
                .householderStatus(householderStatus)
                .majorField(majorField)
                .specialTargetStatus(specialTargets == null ? null : specialTargets.status())
                .specialTargets(specialTargets == null ? new java.util.HashSet<>() : specialTargets.targetsOrEmpty())
                .militaryServiceStatus(militaryService == null ? null : militaryService.status())
                .militaryServiceMonths(militaryService == null ? null : militaryService.months())
                .trainingCardStatus(trainingCardStatus)
                .kdtParticipation(kdtParticipation)
                .otherTrainingParticipation(otherTrainingParticipation)
                .build();
    }
}
