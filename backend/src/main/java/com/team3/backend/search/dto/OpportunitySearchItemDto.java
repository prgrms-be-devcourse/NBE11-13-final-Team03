package com.team3.backend.search.dto;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 검색 목록의 공고 정보와 사용자별 표시 상태를 담는다. */
public record OpportunitySearchItemDto(
        @Schema(description = "공고 ID")
        Long opportunityId,
        @Schema(description = "공고 유형: YOUTH_POLICY 또는 VOCATIONAL_TRAINING")
        OpportunityType type,
        @Schema(description = "공고 제목")
        String title,
        @Schema(description = "대분류")
        String category,
        @Schema(description = "중분류")
        String subCategory,
        @Schema(description = "소분류. 직업훈련은 NCS 2차분류명이고 청년정책은 null이다", nullable = true)
        String detailCategory,
        @Schema(description = "공고 최초 등록 시각")
        LocalDateTime createdAt,
        @Schema(description = "로그인 사용자의 저장된 최신 신청 가능성 판정이다. 비로그인·판정 없음·오래됨·프로필 없음은 null이고, 실제 UNKNOWN 판정은 UNKNOWN이다", nullable = true)
        EligibilityStatus eligibilityStatus,
        @Schema(description = "로그인 사용자의 저장 여부다. 비로그인은 false이며 숨김 상태와 독립적이다")
        boolean saved
) {
    /** 공고 정보에 저장된 신청 가능성·저장 상태를 결합한다. */
    public static OpportunitySearchItemDto from(
            Opportunity opportunity, EligibilityStatus eligibilityStatus, boolean saved
    ) {
        return new OpportunitySearchItemDto(
                opportunity.getId(),
                opportunity.getOpportunityType(),
                opportunity.getTitle(),
                opportunity.getCategory(),
                opportunity.getSubCategory(),
                opportunity.getDetailCategory(),
                opportunity.getCreatedAt(),
                eligibilityStatus,
                saved
        );
    }
}
