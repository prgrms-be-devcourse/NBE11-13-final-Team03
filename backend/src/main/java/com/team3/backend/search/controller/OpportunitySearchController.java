package com.team3.backend.search.controller;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.dto.OpportunitySearchSort;
import com.team3.backend.search.service.OpportunitySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 비로그인 공고 검색 요청을 받는 API다. */
@RestController
@RequiredArgsConstructor
public class OpportunitySearchController {

    private final OpportunitySearchService searchService;

    /** 공고 조건과 유형별 날짜 범위·정렬 기준으로 검색하고 페이지 결과를 반환한다. */
    @GetMapping("/api/opportunities")
    public ResponseEntity<OpportunitySearchResponseDto> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OpportunityType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) ApplicationStatus applicationStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo,
            @RequestParam(defaultValue = "LATEST") OpportunitySearchSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(searchService.search(
                keyword, type, category, subCategory, region, applicationStatus,
                deadlineFrom, deadlineTo, startDateFrom, startDateTo, sort, page, size));
    }
}
