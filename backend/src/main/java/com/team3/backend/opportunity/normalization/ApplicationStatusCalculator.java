package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;

/**
 * 신청기간들과 기준일(한국 시간 날짜)로 현재 신청 상태를 계산한다.
 * 검증 상태가 VALID인 기간만 사용한다. 오류·미확인 기간은 상태 계산에서 빠지고, 남는 기간이 없으면 UNKNOWN.
 *
 * 우선순위: 하나라도 OPEN → OPEN, 상시 → ALWAYS_OPEN, 향후 기간 존재 → UPCOMING, 모두 종료·마감 → CLOSED
 * 시작일만 있는 기간은 시작 후 계속 신청 가능, 종료일만 있는 기간은 종료일까지 신청 가능으로 본다.
 */
@Component
public class ApplicationStatusCalculator {

    public ApplicationStatus calculate(Collection<ApplicationPeriod> periods, LocalDate today) {
        boolean open = false, always = false, upcoming = false, closed = false;

        for (ApplicationPeriod period : periods) {
            if (period.validationStatus() != ValidationStatus.VALID) {
                continue;
            }
            switch (period.periodType()) {
                case ALWAYS -> always = true;
                case CLOSED -> closed = true;
                case FIXED -> {
                    ApplicationStatus status = statusOf(period.startDate(), period.endDate(), today);
                    open |= status == ApplicationStatus.OPEN;
                    upcoming |= status == ApplicationStatus.UPCOMING;
                    closed |= status == ApplicationStatus.CLOSED;
                }
                case UNKNOWN -> {
                }
            }
        }

        if (open) return ApplicationStatus.OPEN;
        if (always) return ApplicationStatus.ALWAYS_OPEN;
        if (upcoming) return ApplicationStatus.UPCOMING;
        if (closed) return ApplicationStatus.CLOSED;
        return ApplicationStatus.UNKNOWN;
    }

    private static ApplicationStatus statusOf(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null && end == null) {
            return ApplicationStatus.UNKNOWN;
        }
        if (start != null && today.isBefore(start)) {
            return ApplicationStatus.UPCOMING;
        }
        if (end != null && today.isAfter(end)) {
            return ApplicationStatus.CLOSED;
        }
        return ApplicationStatus.OPEN;
    }
}
