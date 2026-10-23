package com.team3.dataservice.opportunity.normalization.dto;

import com.team3.dataservice.opportunity.normalization.official.SupplementOutcome;

/**
 * newVersion=false면 정규화 결과가 이전 버전과 같아 새 버전을 만들지 않았다는 뜻이다.
 * ageOutcome/periodOutcome: 마지막으로 반영된 보완 단계의 결과 (아무 보완도 없으면 NOT_APPLIED)
 */
public record NormalizationResult(
        Long opportunityId,
        Long versionId,
        Integer versionNo,
        boolean newOpportunity,
        boolean newVersion,
        SupplementOutcome ageOutcome,
        SupplementOutcome periodOutcome
) {
}
