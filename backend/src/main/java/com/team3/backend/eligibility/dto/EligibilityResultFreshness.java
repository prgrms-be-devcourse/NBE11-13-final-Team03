package com.team3.backend.eligibility.dto;

import java.time.LocalDate;

/** 저장된 판정 결과의 최신 여부 판단용 조회 결과 (조건별 결과 jsonb는 읽지 않는다) */
public record EligibilityResultFreshness(
        Long opportunityId,
        Long opportunityVersionId,
        Long profileVersion,
        String ruleVersion,
        LocalDate validUntil
) {

    public boolean isFresh(Long currentVersionId, Long currentProfileVersion, String currentRuleVersion, LocalDate today) {
        return opportunityVersionId.equals(currentVersionId)
                && profileVersion.equals(currentProfileVersion)
                && ruleVersion.equals(currentRuleVersion)
                && (validUntil == null || today.isBefore(validUntil));
    }
}
