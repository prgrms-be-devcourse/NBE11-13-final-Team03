package com.team3.backend.eligibility.dto;

import com.team3.backend.eligibility.domain.entity.*;

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
    public record Income(AnswerStatus status, BigDecimal amount, IncomePeriod period) {

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
    public record MedianIncome(AnswerStatus status, BigDecimal percent) {
    }

    /** 학자금 지원구간 1~10 */
    public record StudentLoanBracket(AnswerStatus status, Integer bracket) {
    }

    /** 특화 대상 (민감정보) */
    public record SpecialTargets(AnswerStatus status, Set<SpecialTarget> targets) {

        Set<SpecialTarget> targetsOrEmpty() {
            return status == AnswerStatus.PROVIDED && targets != null ? new HashSet<>(targets) : new HashSet<>();
        }
    }

    /** 병역 (연령 상한 예외 판정용) */
    public record MilitaryService(MilitaryServiceStatus status, Integer months) {
    }
}
