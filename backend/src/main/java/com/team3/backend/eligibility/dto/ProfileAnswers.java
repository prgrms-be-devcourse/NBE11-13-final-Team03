package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * 판정 조건 요청·응답에서 함께 쓰는 응답 묶음.
 * 묶음 자체가 null이면 미입력, status=UNKNOWN이면 모름, NOT_APPLICABLE이면 해당 없음.
 */
public final class ProfileAnswers {

    private ProfileAnswers() {
    }

    /** 소득. NOT_APPLICABLE은 받지 않는다 (소득이 없으면 PROVIDED + 0) */
    public record Income(
            @NotNull(message = "소득 응답 상태가 필요합니다.") AnswerStatus status,
            @PositiveOrZero(message = "소득은 음수일 수 없습니다.") BigDecimal amount,
            IncomePeriod period) {

        public static Income from(IncomeAnswer answer) {
            return answer == null || answer.getStatus() == null
                    ? null
                    : new Income(answer.getStatus(), answer.getAmount(), answer.getPeriod());
        }

        IncomeAnswer toEntity() {
            if (status == null) {
                throw new IllegalArgumentException("소득 응답 상태가 필요합니다.");
            }
            return switch (status) {
                case PROVIDED -> IncomeAnswer.provided(amount, period);
                case UNKNOWN -> IncomeAnswer.unknown();
                case NOT_APPLICABLE -> throw new IllegalArgumentException("소득에는 해당 없음을 쓸 수 없습니다. 소득이 없으면 0을 입력하세요.");
            };
        }
    }

    /** 기준중위소득 대비 비율(%) */
    public record MedianIncome(
            @NotNull(message = "기준중위소득 응답 상태가 필요합니다.") AnswerStatus status,
            @DecimalMin(value = "0", message = "기준중위소득 비율은 0~999.99입니다.")
            @DecimalMax(value = "999.99", message = "기준중위소득 비율은 0~999.99입니다.")
            @Digits(integer = 3, fraction = 2, message = "기준중위소득 비율은 소수 둘째 자리까지입니다.")
            BigDecimal percent) {
    }

    /** 학자금 지원구간 1~10 */
    public record StudentLoanBracket(
            @NotNull(message = "학자금 지원구간 응답 상태가 필요합니다.") AnswerStatus status,
            @Min(value = 1, message = "학자금 지원구간은 1~10입니다.") @Max(value = 10, message = "학자금 지원구간은 1~10입니다.")
            Integer bracket) {
    }

    /** 특화 대상 (민감정보) */
    public record SpecialTargets(
            @NotNull(message = "특화 대상 응답 상태가 필요합니다.") AnswerStatus status,
            Set<SpecialTarget> targets) {

        Set<SpecialTarget> targetsOrEmpty() {
            return status == AnswerStatus.PROVIDED && targets != null ? new HashSet<>(targets) : new HashSet<>();
        }
    }

    /** 병역 (연령 상한 예외 판정용) */
    public record MilitaryService(
            @NotNull(message = "병역 상태가 필요합니다.") MilitaryServiceStatus status,
            @PositiveOrZero(message = "병역 개월 수는 음수일 수 없습니다.") Integer months) {
    }
}
