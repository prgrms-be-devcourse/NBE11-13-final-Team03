package com.team3.dataservice.opportunity.dto;

import com.team3.dataservice.opportunity.domain.entity.OpportunityType;

import java.time.LocalDateTime;

/** 다른 영역에 넘길 기회 요약 조회 결과 (정규화 스냅샷은 읽지 않는다) */
public record OpportunityInfoRow(
        Long opportunityId,
        Long versionId,
        OpportunityType opportunityType,
        String title,
        String category,
        String subCategory,
        String detailUrl,
        LocalDateTime createdAt
) {
}
