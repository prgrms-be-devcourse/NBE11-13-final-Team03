package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;

import java.time.LocalDate;
import java.util.Optional;

/**
 * PATCH /api/users/me/eligibility-profile 요청 (JSON Merge Patch 방식 부분 수정).
 * 각 필드의 의미
 * - 필드를 보내지 않음 (값이 null): 기존 값 유지
 * - 명시적으로 null을 보냄 (Optional.empty()): 미입력 상태로 변경
 * - 값을 보냄 (Optional.of(값)): 그 값으로 변경
 * 수정한 결과 전체는 PUT과 같은 검증을 다시 거친다 (필수 항목을 null로 보내면 검증 오류).
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public record EligibilityProfilePatchRequest(
        Optional<LocalDate> birthDate,
        Optional<String> residenceRegionCode,
        Optional<EmploymentStatus> employmentStatus,
        Optional<EducationLevel> educationLevel,
        Optional<EnrollmentStatus> enrollmentStatus,
        Optional<String> schoolRegionCode,
        Optional<MaritalStatus> maritalStatus,
        Optional<HousingStatus> housingStatus,
        Optional<YesNoUnknown> homelessStatus,
        Optional<ProfileAnswers.Income> personalIncome,
        Optional<ProfileAnswers.Income> householdIncome,
        Optional<ProfileAnswers.MedianIncome> medianIncome,
        Optional<ProfileAnswers.StudentLoanBracket> studentLoanBracket,
        Optional<Integer> householdSize,
        Optional<YesNoUnknown> householderStatus,
        Optional<MajorField> majorField,
        Optional<ProfileAnswers.SpecialTargets> specialTargets,
        Optional<ProfileAnswers.MilitaryService> militaryService,
        Optional<TrainingCardStatus> trainingCardStatus,
        Optional<TrainingParticipation> kdtParticipation,
        Optional<TrainingParticipation> otherTrainingParticipation
) {

    /** 현재 프로필 값에 이 요청을 덮어쓴 전체 교체 요청을 만든다 */
    public EligibilityProfileUpdateRequest mergeInto(EligibilityProfileResponse current) {
        return new EligibilityProfileUpdateRequest(
                merge(birthDate, current.birthDate()),
                merge(residenceRegionCode, current.residenceRegionCode()),
                merge(employmentStatus, current.employmentStatus()),
                merge(educationLevel, current.educationLevel()),
                merge(enrollmentStatus, current.enrollmentStatus()),
                merge(schoolRegionCode, current.schoolRegionCode()),
                merge(maritalStatus, current.maritalStatus()),
                merge(housingStatus, current.housingStatus()),
                merge(homelessStatus, current.homelessStatus()),
                merge(personalIncome, current.personalIncome()),
                merge(householdIncome, current.householdIncome()),
                merge(medianIncome, current.medianIncome()),
                merge(studentLoanBracket, current.studentLoanBracket()),
                merge(householdSize, current.householdSize()),
                merge(householderStatus, current.householderStatus()),
                merge(majorField, current.majorField()),
                merge(specialTargets, current.specialTargets()),
                merge(militaryService, current.militaryService()),
                merge(trainingCardStatus, current.trainingCardStatus()),
                merge(kdtParticipation, current.kdtParticipation()),
                merge(otherTrainingParticipation, current.otherTrainingParticipation()));
    }

    private static <T> T merge(Optional<T> patch, T current) {
        return patch == null ? current : patch.orElse(null);
    }
}
