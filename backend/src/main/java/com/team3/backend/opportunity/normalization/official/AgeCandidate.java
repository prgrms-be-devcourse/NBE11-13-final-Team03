package com.team3.backend.opportunity.normalization.official;

import com.team3.backend.opportunity.normalization.AgeStatus;

import java.time.LocalDate;

/** 원문 한 줄에서 찾은 연령 조건 후보 */
public record AgeCandidate(
        AgeStatus status,
        Integer min,
        Integer max,
        LocalDate bornOnOrAfter,
        LocalDate bornOnOrBefore,
        String line
) {

    boolean sameRule(AgeCandidate other) {
        return status == other.status
                && java.util.Objects.equals(min, other.min)
                && java.util.Objects.equals(max, other.max)
                && java.util.Objects.equals(bornOnOrAfter, other.bornOnOrAfter)
                && java.util.Objects.equals(bornOnOrBefore, other.bornOnOrBefore);
    }
}
