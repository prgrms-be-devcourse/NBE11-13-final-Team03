package com.team3.backend.search.controller;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.search.dto.OpportunitySearchResponseDto;
import com.team3.backend.search.service.OpportunitySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OpportunitySearchController {

    private final OpportunitySearchService searchService;

    @GetMapping("/api/opportunities")
    public ResponseEntity<OpportunitySearchResponseDto> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OpportunityType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(searchService.search(
                keyword, type, category, subCategory, page, size));
    }
}
