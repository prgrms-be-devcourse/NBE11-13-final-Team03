package com.team3.backend.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 검색 결과와 0-based 페이지 메타데이터를 담는다. */
public record OpportunitySearchResponseDto(
        @Schema(description = "현재 페이지의 검색 결과다. 숨김 공고도 포함한다")
        List<OpportunitySearchItemDto> content,
        @Schema(description = "0부터 시작하는 현재 페이지 번호")
        int page,
        @Schema(description = "요청한 페이지 크기")
        int size,
        @Schema(description = "모든 검색 조건을 적용한 전체 공고 수")
        long totalElements,
        @Schema(description = "전체 페이지 수")
        int totalPages,
        @Schema(description = "다음 페이지 존재 여부")
        boolean hasNext
) {
}
