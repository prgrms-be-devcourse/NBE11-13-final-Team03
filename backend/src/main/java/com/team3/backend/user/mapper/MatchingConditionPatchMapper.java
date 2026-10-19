package com.team3.backend.user.mapper;

import com.team3.backend.eligibility.domain.entity.*;
import com.team3.backend.eligibility.dto.EligibilityProfilePatchRequest;
import com.team3.backend.eligibility.dto.ProfileAnswers;
import com.team3.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.Optional;

import static com.team3.backend.eligibility.exception.EligibilityErrorCode.INVALID_PROFILE_VALUE;

@Component
@RequiredArgsConstructor
public class MatchingConditionPatchMapper {

    private final ObjectMapper objectMapper;

    public EligibilityProfilePatchRequest from(ObjectNode json) {
        return new EligibilityProfilePatchRequest(
                field(json, "birthDate", LocalDate.class),
                field(json, "residenceRegionCode", String.class),
                field(json, "employmentStatus", EmploymentStatus.class),
                field(json, "educationLevel", EducationLevel.class),
                field(json, "enrollmentStatus", EnrollmentStatus.class),
                field(json, "schoolRegionCode", String.class),
                field(json, "maritalStatus", MaritalStatus.class),
                field(json, "housingStatus", HousingStatus.class),
                field(json, "homelessStatus", YesNoUnknown.class),
                field(json, "personalIncome", ProfileAnswers.Income.class),
                field(json, "householdIncome", ProfileAnswers.Income.class),
                field(json, "medianIncome", ProfileAnswers.MedianIncome.class),
                field(json, "studentLoanBracket", ProfileAnswers.StudentLoanBracket.class),
                field(json, "householdSize", Integer.class),
                field(json, "householderStatus", YesNoUnknown.class),
                field(json, "majorField", MajorField.class),
                field(json, "specialTargets", ProfileAnswers.SpecialTargets.class),
                field(json, "militaryService", ProfileAnswers.MilitaryService.class),
                field(json, "trainingCardStatus", TrainingCardStatus.class),
                field(json, "kdtParticipation", TrainingParticipation.class),
                field(json, "otherTrainingParticipation", TrainingParticipation.class)
        );
    }

    /**
     * 필드 없음      → null            → 기존 값 유지
     * 명시적인 null → Optional.empty() → 기존 값 삭제
     * 값 전달       → Optional.of()    → 전달값으로 변경
     */
    private <T> Optional<T> field(
            ObjectNode json,
            String fieldName,
            Class<T> type
    ) {
        if (!json.has(fieldName)) {
            return null;
        }

        JsonNode value = json.get(fieldName);

        if (value == null || value.isNull()) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    objectMapper.treeToValue(value, type)
            );
        } catch (JacksonException exception) {
            throw new BusinessException(
                    INVALID_PROFILE_VALUE,
                    exception
            );
        }
    }
}