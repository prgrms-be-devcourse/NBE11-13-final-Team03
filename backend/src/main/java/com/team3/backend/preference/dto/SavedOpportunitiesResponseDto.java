package com.team3.backend.preference.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 저장 공고 목록과 0-based 페이지 메타데이터를 담는다. */
public record SavedOpportunitiesResponseDto(
        @Schema(description = "현재 페이지의 저장 공고")
        List<SavedOpportunityResponseDto> content,
        @Schema(description = "0부터 시작하는 현재 페이지 번호")
        int page,
        @Schema(description = "요청한 페이지 크기")
        int size,
        @Schema(description = "저장 공고 전체 수")
        long totalElements,
        @Schema(description = "전체 페이지 수")
        int totalPages,
        @Schema(description = "다음 페이지 존재 여부")
        boolean hasNext
) {
}
