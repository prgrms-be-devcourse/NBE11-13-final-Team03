package com.team3.backend.eligibility.dto;

import java.time.LocalDate;

/** 저장된 판정 결과의 최신 여부 판단용 조회 결과 (조건별 결과 jsonb는 읽지 않는다). 조건 묶음으로 찾았으므로 묶음은 이미 같다 */
public record EligibilityResultFreshness(
        Long conditionSetId,
        Long profileVersion,
        String ruleVersion,
        LocalDate validUntil
) {

    public boolean isFresh(Long currentProfileVersion, String currentRuleVersion, LocalDate today) {
        return profileVersion.equals(currentProfileVersion)
                && ruleVersion.equals(currentRuleVersion)
                && (validUntil == null || today.isBefore(validUntil));
    }
}
