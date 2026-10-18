package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.region.domain.entity.Region;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * GET·PUT /api/users/me/eligibility-profile 응답. 요청과 같은 구조에 updatedAt을 더한다.
 * null인 항목은 미입력이다.
 */
public record EligibilityProfileResponse(
        LocalDate birthDate,
        String residenceRegionCode,
        EmploymentStatus employmentStatus,
        EducationLevel educationLevel,
        EnrollmentStatus enrollmentStatus,
        String schoolRegionCode,
        MaritalStatus maritalStatus,
        HousingStatus housingStatus,
        YesNoUnknown homelessStatus,
        ProfileAnswers.Income personalIncome,
        ProfileAnswers.Income householdIncome,
        ProfileAnswers.MedianIncome medianIncome,
        ProfileAnswers.StudentLoanBracket studentLoanBracket,
        Integer householdSize,
        YesNoUnknown householderStatus,
        MajorField majorField,
        ProfileAnswers.SpecialTargets specialTargets,
        ProfileAnswers.MilitaryService militaryService,
        TrainingCardStatus trainingCardStatus,
        TrainingParticipation kdtParticipation,
        TrainingParticipation otherTrainingParticipation,
        OffsetDateTime updatedAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static EligibilityProfileResponse from(EligibilityProfile p) {
        return new EligibilityProfileResponse(
                p.getBirthDate(),
                codeOf(p.getResidenceRegion()),
                p.getEmploymentStatus(),
                p.getEducationLevel(),
                p.getEnrollmentStatus(),
                codeOf(p.getSchoolRegion()),
                p.getMaritalStatus(),
                p.getHousingStatus(),
                p.getHomelessStatus(),
                ProfileAnswers.Income.from(p.getPersonalIncome()),
                ProfileAnswers.Income.from(p.getHouseholdIncome()),
                p.getMedianIncomeStatus() == null ? null
                        : new ProfileAnswers.MedianIncome(p.getMedianIncomeStatus(), p.getMedianIncomePercent()),
                p.getStudentLoanBracketStatus() == null ? null
                        : new ProfileAnswers.StudentLoanBracket(p.getStudentLoanBracketStatus(), p.getStudentLoanBracket()),
                p.getHouseholdSize(),
                p.getHouseholderStatus(),
                p.getMajorField(),
                p.getSpecialTargetStatus() == null ? null
                        : new ProfileAnswers.SpecialTargets(p.getSpecialTargetStatus(), Set.copyOf(p.getSpecialTargets())),
                p.getMilitaryServiceStatus() == null ? null
                        : new ProfileAnswers.MilitaryService(p.getMilitaryServiceStatus(), p.getMilitaryServiceMonths()),
                p.getTrainingCardStatus(),
                p.getKdtParticipation(),
                p.getOtherTrainingParticipation(),
                p.getUpdatedAt() == null ? null : p.getUpdatedAt().atZone(KST).toOffsetDateTime()
        );
    }

    private static String codeOf(Region region) {
        return region == null ? null : region.getRegionCode();
    }
}
