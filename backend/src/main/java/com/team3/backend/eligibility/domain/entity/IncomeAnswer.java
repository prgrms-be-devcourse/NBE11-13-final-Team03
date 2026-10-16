package com.team3.backend.eligibility.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 소득 응답. 개인 소득과 가구 소득에 각각 사용한다.
 * 임베디드 값 전체가 NULL이면 미입력, status=UNKNOWN이면 모름.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncomeAnswer {

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AnswerStatus status;

    @Column(precision = 15, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private IncomePeriod period;

    private IncomeAnswer(AnswerStatus status, BigDecimal amount, IncomePeriod period) {
        this.status = status;
        this.amount = amount;
        this.period = period;
    }

    public static IncomeAnswer provided(BigDecimal amount, IncomePeriod period) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(period, "period");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("소득은 음수일 수 없습니다.");
        }
        return new IncomeAnswer(AnswerStatus.PROVIDED, amount, period);
    }

    public static IncomeAnswer unknown() {
        return new IncomeAnswer(AnswerStatus.UNKNOWN, null, null);
    }
}
