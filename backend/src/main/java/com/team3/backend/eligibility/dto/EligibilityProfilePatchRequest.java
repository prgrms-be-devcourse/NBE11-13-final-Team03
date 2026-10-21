package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * PATCH /api/users/me/eligibility-profile 요청 (JSON Merge Patch 방식 부분 수정).
 * 각 필드의 의미
 * - 필드를 보내지 않음 (값이 null): 기존 값 유지
 * - 명시적으로 null을 보냄 (Optional.empty()): 미입력 상태로 변경
 * - 값을 보냄 (Optional.of(값)): 그 값으로 변경
 * 수정한 결과 전체는 PUT과 같은 검증을 다시 거친다 (필수 항목을 null로 보내면 검증 오류).
 *
 * Jackson은 레코드의 Optional 필드에 대해 "보내지 않음"과 "null"을 모두 Optional.empty()로 만들어 구분이 사라진다.
 * 그래서 요청 본문은 Map으로 받아 fromJson으로 만든다 (키가 있는지로 두 경우를 구분).
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

    /** 요청 본문(JSON 객체) → 부분 수정 요청. 키가 없으면 null(유지), 키가 있고 값이 null이면 Optional.empty()(미입력) */
    public static EligibilityProfilePatchRequest fromJson(Map<String, ?> body, ObjectMapper mapper) {
        EligibilityProfileUpdateRequest v = mapper.convertValue(body, EligibilityProfileUpdateRequest.class);
        return new EligibilityProfilePatchRequest(
                field(body, "birthDate", v.birthDate()),
                field(body, "residenceRegionCode", v.residenceRegionCode()),
                field(body, "employmentStatus", v.employmentStatus()),
                field(body, "educationLevel", v.educationLevel()),
                field(body, "enrollmentStatus", v.enrollmentStatus()),
                field(body, "schoolRegionCode", v.schoolRegionCode()),
                field(body, "maritalStatus", v.maritalStatus()),
                field(body, "housingStatus", v.housingStatus()),
                field(body, "homelessStatus", v.homelessStatus()),
                field(body, "personalIncome", v.personalIncome()),
                field(body, "householdIncome", v.householdIncome()),
                field(body, "medianIncome", v.medianIncome()),
                field(body, "studentLoanBracket", v.studentLoanBracket()),
                field(body, "householdSize", v.householdSize()),
                field(body, "householderStatus", v.householderStatus()),
                field(body, "majorField", v.majorField()),
                field(body, "specialTargets", v.specialTargets()),
                field(body, "militaryService", v.militaryService()),
                field(body, "trainingCardStatus", v.trainingCardStatus()),
                field(body, "kdtParticipation", v.kdtParticipation()),
                field(body, "otherTrainingParticipation", v.otherTrainingParticipation()));
    }

    private static <T> Optional<T> field(Map<String, ?> body, String name, T value) {
        return body.containsKey(name) ? Optional.ofNullable(value) : null;
    }

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
