package com.team3.backend.search.dto;

import com.team3.backend.eligibility.api.dto.ConditionResult;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshot;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.normalization.ApplicationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 공고의 현재 정규화 정보와 사용자별 최신 판정·저장 상태를 구분해 전달한다. */
public record OpportunityDetailResponseDto(
        Long opportunityId,
        OpportunityType type,
        String title,
        String category,
        String subCategory,
        String detailUrl,
        LocalDateTime createdAt,
        List<RegionItem> regions,
        List<ApplicationWindowItem> applicationWindows,
        ApplicationStatus applicationStatus,
        List<ConditionItem> conditions,
        Detail detail,
        EligibilityItem eligibility,
        boolean saved
) {
    /** 지역 행이 없어도 전국·미확인 상태를 구별한다. */
    public record RegionItem(RegionRole role, String status, String code, String name,
                             ValidationStatus validationStatus) {
    }

    /** 사업기간이나 교육기간과 구분되는 실제 신청기간이다. */
    public record ApplicationWindowItem(PeriodType periodType, LocalDate startDate, LocalDate endDate,
                                        String periodText, ValidationStatus validationStatus) {
        public static ApplicationWindowItem from(ApplicationWindow window) {
            return new ApplicationWindowItem(window.getPeriodType(), window.getStartDate(), window.getEndDate(),
                    window.getPeriodText(), window.getValidationStatus());
        }
    }

    /** 검증된 구조화 값과 검토가 필요한 근거 문장을 구분하며 내부 JSON은 노출하지 않는다. */
    public record ConditionItem(ConditionType type, RequirementType requirement, String field,
                                ValidationStatus validationStatus, String status, Number min, Number max,
                                List<String> values, String bornOnOrAfter, String bornOnOrBefore,
                                String incomeType, String period, String subject,
                                Boolean minInclusive, Boolean maxInclusive, Boolean unitConfirmed,
                                String evidenceText, String reason) {
        public static ConditionItem from(OpportunityCondition condition) {
            Map<String, Object> expression = condition.getExpression();
            Map<String, Object> evidence = condition.getEvidence();
            boolean valid = condition.getValidationStatus() == ValidationStatus.VALID;
            boolean age = valid && condition.getConditionType() == ConditionType.AGE;
            boolean income = valid && condition.getConditionType() == ConditionType.INCOME;
            Object rawValues = expression.get("values");
            List<String> values = valid && rawValues instanceof List<?> list
                    ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
            return new ConditionItem(condition.getConditionType(), condition.getRequirementType(),
                    condition.getConditionField(), condition.getValidationStatus(),
                    valid ? stringValue(expression.get("status")) : null,
                    age || income ? bound(expression.get("min"), income) : null,
                    age || income ? bound(expression.get("max"), income) : null,
                    values,
                    age ? stringValue(expression.get("bornOnOrAfter")) : null,
                    age ? stringValue(expression.get("bornOnOrBefore")) : null,
                    income ? stringValue(expression.get("type")) : null,
                    income ? stringValue(expression.get("period")) : null,
                    income ? stringValue(expression.get("subject")) : null,
                    income ? booleanValue(expression.get("minInclusive")) : null,
                    income ? booleanValue(expression.get("maxInclusive")) : null,
                    income ? booleanValue(expression.get("unitConfirmed")) : null,
                    evidence == null ? null : stringValue(evidence.get("evidenceText")),
                    evidence == null ? null : stringValue(evidence.get("reason")));
        }

        private static Number bound(Object value, boolean income) {
            if (value instanceof Number number) {
                return number;
            }
            // 소득 정규화는 소수만 JSON 비교 안정성을 위해 문자열로 저장한다.
            if (income && value instanceof String text && text.matches("-?\\d+\\.\\d+")) {
                return new BigDecimal(text);
            }
            return null;
        }

        private static Boolean booleanValue(Object value) {
            return value instanceof Boolean flag ? flag : null;
        }

        private static String stringValue(Object value) {
            return value instanceof String text ? text : null;
        }
    }

    /** 유형마다 저장된 사용자용 상세 필드를 분리한다. */
    public sealed interface Detail permits YouthPolicyDetailItem, TrainingCourseDetailItem {
    }

    /** 정책 설명과 사업기간을 신청기간과 분리해 제공한다. */
    public record YouthPolicyDetailItem(String policyNo, String description, String supportContent,
                                        String keywords, String supervisingInstitutionName,
                                        String operatingInstitutionName, String applicationMethod,
                                        String screeningMethod, String submissionDocuments,
                                        String applicationUrl, String referenceUrl1, String referenceUrl2,
                                        LocalDate businessStartDate, LocalDate businessEndDate,
                                        String businessPeriodText, String supportScale, String otherNotes,
                                        LocalDate firstRegisteredDate) implements Detail {
        public static YouthPolicyDetailItem from(YouthPolicyDetail detail) {
            return new YouthPolicyDetailItem(detail.getPolicyNo(), detail.getDescription(), detail.getSupportContent(),
                    detail.getKeywords(), detail.getSupervisingInstitutionName(), detail.getOperatingInstitutionName(),
                    detail.getApplicationMethod(), detail.getScreeningMethod(), detail.getSubmissionDocuments(),
                    detail.getApplicationUrl(), detail.getReferenceUrl1(), detail.getReferenceUrl2(),
                    detail.getBusinessStartDate(), detail.getBusinessEndDate(), detail.getBusinessPeriodText(),
                    detail.getSupportScale(), detail.getOtherNotes(), detail.getFirstRegisteredDate());
        }
    }

    /** 교육 시작일은 신청 마감일이 아닌 훈련 일정으로 제공한다. */
    public record TrainingCourseDetailItem(String courseId, Integer courseRound, String institutionName,
                                           String institutionCode, String institutionCustomerId,
                                           LocalDate trainingStartDate, LocalDate trainingEndDate,
                                           Long actualTrainingCost, Long courseFee, Integer capacity,
                                           String ncsCode, String trainingTargetCode, String trainingTargetName,
                                           String weekendTypeCode, String address, String telNo) implements Detail {
        public static TrainingCourseDetailItem from(TrainingCourseDetail detail) {
            return new TrainingCourseDetailItem(detail.getCourseId(), detail.getCourseRound(),
                    detail.getInstitutionName(), detail.getInstitutionCode(), detail.getInstitutionCustomerId(),
                    detail.getTrainingStartDate(), detail.getTrainingEndDate(), detail.getActualTrainingCost(),
                    detail.getCourseFee(), detail.getCapacity(), detail.getNcsCode(), detail.getTrainingTargetCode(),
                    detail.getTrainingTargetName(), detail.getWeekendTypeCode(), detail.getAddress(), detail.getTelNo());
        }
    }

    /** 미판정 null과 실제 UNKNOWN 판정을 구분한다. */
    public record EligibilityItem(EligibilityStatus status, String reason, List<EligibilityConditionItem> conditions) {
        public static EligibilityItem from(EligibilitySnapshot snapshot) {
            return new EligibilityItem(snapshot.status(), snapshot.reason(), snapshot.conditions().stream()
                    .map(EligibilityConditionItem::from).toList());
        }
    }

    /** 내부 판정 입력값 대신 조건별 판정과 설명만 노출한다. */
    public record EligibilityConditionItem(String type, String field, String requirement,
                                           String result, String reason) {
        public static EligibilityConditionItem from(ConditionResult condition) {
            return new EligibilityConditionItem(condition.type(), condition.field(), condition.requirement(),
                    condition.result().name(), condition.reason());
        }
    }
}
