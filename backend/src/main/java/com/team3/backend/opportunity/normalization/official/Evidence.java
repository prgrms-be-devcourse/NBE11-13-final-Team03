package com.team3.backend.opportunity.normalization.official;

import java.time.OffsetDateTime;

/**
 * 조건 근거 (OpportunityCondition.evidence JSON으로 저장될 값).
 */
public record Evidence(
        SourceType sourceType,
        String sourceUrl,
        String sourceHash,
        String evidenceText,
        Confidence confidence,
        boolean dataConflict,
        OffsetDateTime verifiedAt
) {
}
