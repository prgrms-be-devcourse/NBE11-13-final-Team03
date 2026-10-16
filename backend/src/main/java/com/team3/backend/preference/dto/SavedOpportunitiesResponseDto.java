package com.team3.backend.preference.dto;

import java.util.List;

public record SavedOpportunitiesResponseDto(
        List<SavedOpportunityResponseDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
