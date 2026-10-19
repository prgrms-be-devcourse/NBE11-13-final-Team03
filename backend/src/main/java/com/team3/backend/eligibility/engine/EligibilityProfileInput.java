package com.team3.backend.eligibility.engine;

import com.team3.backend.eligibility.domain.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * 판정 엔진의 프로필 입력. 엔진이 프로필에서 읽는 값만 담고 JPA 엔티티에 의존하지 않는다.
 * 판정 묶음마다 한 번 만들어 모든 정책 판정에 같이 쓴다 (특화대상 지연 로딩도 이때 한 번만 일어난다).
 * 엔진에 새 판정 규칙을 추가해 프로필 값이 더 필요해지면 여기에 필드를 추가한다.
 */
public record EligibilityProfileInput(
        LocalDate birthDate,
        String residenceRegionCode,
        MaritalStatus maritalStatus,
        EmploymentStatus employmentStatus,
        EducationLevel educationLevel,
        EnrollmentStatus enrollmentStatus,
        MajorField majorField,
        AnswerStatus specialTargetStatus,
        Set<SpecialTarget> specialTargets,
        AnswerStatus medianIncomeStatus,
        BigDecimal medianIncomePercent,
        AnswerStatus studentLoanBracketStatus,
        Integer studentLoanBracket,
        Income personalIncome,
        Income householdIncome,
        TrainingCardStatus trainingCardStatus,
        TrainingParticipation kdtParticipation
) {

    /** 개인·가구 소득 응답 (IncomeAnswer와 같은 값) */
    public record Income(AnswerStatus status, BigDecimal amount, IncomePeriod period) {

        static Income from(IncomeAnswer answer) {
            return answer == null ? null : new Income(answer.getStatus(), answer.getAmount(), answer.getPeriod());
        }
    }

    public EligibilityProfileInput {
        specialTargets = specialTargets == null ? Set.of() : Set.copyOf(specialTargets);
    }

    /** 특화대상 목록은 응답 상태가 PROVIDED일 때만 읽는다 (엔진도 그때만 쓴다) */
    public static EligibilityProfileInput from(EligibilityProfile p) {
        return new EligibilityProfileInput(
                p.getBirthDate(),
                p.getResidenceRegion() == null ? null : p.getResidenceRegion().getRegionCode(),
                p.getMaritalStatus(),
                p.getEmploymentStatus(),
                p.getEducationLevel(),
                p.getEnrollmentStatus(),
                p.getMajorField(),
                p.getSpecialTargetStatus(),
                p.getSpecialTargetStatus() == AnswerStatus.PROVIDED ? p.getSpecialTargets() : Set.of(),
                p.getMedianIncomeStatus(),
                p.getMedianIncomePercent(),
                p.getStudentLoanBracketStatus(),
                p.getStudentLoanBracket(),
                Income.from(p.getPersonalIncome()),
                Income.from(p.getHouseholdIncome()),
                p.getTrainingCardStatus(),
                p.getKdtParticipation());
    }
}
