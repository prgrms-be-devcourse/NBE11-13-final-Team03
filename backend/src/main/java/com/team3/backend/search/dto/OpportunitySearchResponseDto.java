package com.team3.backend.search.dto;

import java.util.List;

public record OpportunitySearchResponseDto(
        List<OpportunitySearchItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
