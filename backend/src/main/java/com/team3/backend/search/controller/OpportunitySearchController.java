package com.team3.backend.search.controller;

import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.security.CustomUserDetails;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.dto.OpportunitySearchSort;
import com.team3.backend.search.service.OpportunitySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 공개 공고 검색과 인증 사용자 신청 가능성 필터 요청을 받는 API다. */
@RestController
@RequiredArgsConstructor
public class OpportunitySearchController {

    private final OpportunitySearchService searchService;

    /** 공고 조건·신청 가능성·유형별 날짜 범위·정렬 기준으로 검색하고 페이지 결과를 반환한다. */
    @GetMapping("/api/opportunities")
    public ResponseEntity<OpportunitySearchResponseDto> search(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OpportunityType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) ApplicationStatus applicationStatus,
            @RequestParam(required = false) List<EligibilityStatus> eligibilityStatuses,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDateTo,
            @RequestParam(defaultValue = "LATEST") OpportunitySearchSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (eligibilityStatuses != null && !eligibilityStatuses.isEmpty() && userDetails == null) {
            throw new BusinessException(AuthErrorCode.ACCESS_TOKEN_NOT_FOUND);
        }
        return ResponseEntity.ok(searchService.search(
                keyword, type, category, subCategory, region, applicationStatus,
                userDetails == null ? null : userDetails.getUserId(), eligibilityStatuses,
                deadlineFrom, deadlineTo, startDateFrom, startDateTo, sort, page, size));
    }
}
