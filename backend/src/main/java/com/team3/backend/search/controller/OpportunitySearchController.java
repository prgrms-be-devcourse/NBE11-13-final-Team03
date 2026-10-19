package com.team3.backend.search.controller;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.service.OpportunitySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 비로그인 공고 검색 요청을 받는 API다. */
@RestController
@RequiredArgsConstructor
public class OpportunitySearchController {

    private final OpportunitySearchService searchService;

    /** 공고 조건과 선택한 모집 상태로 검색하고 페이지 결과를 반환한다. */
    @GetMapping("/api/opportunities")
    public ResponseEntity<OpportunitySearchResponseDto> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OpportunityType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) ApplicationStatus applicationStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(searchService.search(
                keyword, type, category, subCategory, region, applicationStatus, page, size));
    }
}
