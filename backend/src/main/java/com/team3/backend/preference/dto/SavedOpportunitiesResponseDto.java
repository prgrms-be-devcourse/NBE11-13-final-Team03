package com.team3.backend.preference.dto;

import java.util.List;

/** 저장 공고 목록과 0-based 페이지 메타데이터를 담는다. */
public record SavedOpportunitiesResponseDto(
        List<SavedOpportunityResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
