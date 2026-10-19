package com.team3.backend.search.dto;

import java.util.List;

/** 검색 결과와 0-based 페이지 메타데이터를 담는다. */
public record OpportunitySearchResponseDto(
        List<OpportunitySearchItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
