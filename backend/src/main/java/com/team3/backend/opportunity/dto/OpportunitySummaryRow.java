package com.team3.backend.opportunity.dto;

import com.team3.backend.opportunity.domain.entity.OpportunityType;

/** 판정 목록용 기회 요약 조회 결과 (정규화 스냅샷 등 판정에 쓰지 않는 컬럼은 읽지 않는다) */
public record OpportunitySummaryRow(
        Long opportunityId,
        Long versionId,
        OpportunityType opportunityType,
        String title,
        String category
) {
}
