package com.team3.backend.recommendation.service;

import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.recommendation.dto.RecommendationItemDto;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 선정 결과에 설명과 사용자 저장 상태를 보강하여 추천 목록을 제공한다. */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CANDIDATE_BATCH_SIZE = 100;

    private final RecommendationSelectionService selectionService;
    private final RecommendationReasonService reasonService;
    private final PreferenceService preferenceService;
    private final RecommendationRegionService regionService;

    /** 요청당 한 번 정한 날짜를 선정과 이유 생성에 동일하게 전달한다. */
    @Transactional(readOnly = true)
    public List<RecommendationItemDto> recommend(Long userId) {
        return recommend(userId, LocalDate.now(KST));
    }

    List<RecommendationItemDto> recommend(Long userId, LocalDate today) {
        List<RecommendationEligibleCandidate> selected = selectionService.select(userId, CANDIDATE_BATCH_SIZE, today);
        if (selected.isEmpty()) {
            return List.of();
        }
        List<Long> opportunityIds = selected.stream().map(item -> item.candidate().opportunityId()).toList();
        Set<Long> savedIds = preferenceService.getSavedOpportunityIds(userId, opportunityIds);
        Map<Long, List<OpportunityDetailResponseDto.RegionItem>> regions = regionService.getRegions(opportunityIds);
        return selected.stream().map(item -> toItem(item, savedIds, regions, today)).toList();
    }

    private RecommendationItemDto toItem(RecommendationEligibleCandidate item, Set<Long> savedIds,
                                         Map<Long, List<OpportunityDetailResponseDto.RegionItem>> regions,
                                         LocalDate today) {
        RecommendationCandidate candidate = item.candidate();
        return new RecommendationItemDto(candidate.opportunityId(), candidate.type(), candidate.title(),
                candidate.category(), candidate.subCategory(), candidate.detailCategory(),
                regions.getOrDefault(candidate.opportunityId(), List.of()),
                candidate.applicationStatus(), item.eligibilityStatus(), candidate.applicationEndDate(),
                candidate.trainingStartDate(), candidate.createdAt(), savedIds.contains(candidate.opportunityId()),
                reasonService.create(item, today));
    }
}
