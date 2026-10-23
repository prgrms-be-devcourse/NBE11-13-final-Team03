package com.team3.backend.opportunity.normalization;

import com.team3.backend.opportunity.domain.entity.ValidationStatus;

import java.time.LocalDate;
import java.util.Map;

/**
 * 연령 정규화 결과.
 * raw: 판정에 쓴 원본 값 그대로 (빈 문자열 포함), reason: 판정 사유 (로그·검토용)
 * dataConflict: 원본 필드끼리 모순 (예: 0/0/Y) 또는 API와 공식 원문이 다름
 * bornOnOrAfter/bornOnOrBefore: DATE_BOUND일 때 출생일 기준
 */
public record AgeNormalization(
        AgeStatus status,
        Integer min,
        Integer max,
        LocalDate bornOnOrAfter,
        LocalDate bornOnOrBefore,
        ValidationStatus validationStatus,
        boolean dataConflict,
        String reason,
        Map<String, String> raw
) {

    public AgeNormalization(AgeStatus status, Integer min, Integer max, ValidationStatus validationStatus,
                            boolean dataConflict, String reason, Map<String, String> raw) {
        this(status, min, max, null, null, validationStatus, dataConflict, reason, raw);
    }

    /** 같은 연령 조건인지 (검증 상태·사유·원본 제외) */
    public boolean sameRule(AgeNormalization other) {
        return status == other.status
                && java.util.Objects.equals(min, other.min)
                && java.util.Objects.equals(max, other.max)
                && java.util.Objects.equals(bornOnOrAfter, other.bornOnOrAfter)
                && java.util.Objects.equals(bornOnOrBefore, other.bornOnOrBefore);
    }
}
