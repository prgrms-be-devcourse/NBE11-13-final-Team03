package com.team3.backend.recommendation.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.repository.OpportunityCategoryRepository;
import com.team3.backend.opportunity.dto.OpportunityCategoryItem;
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

    private final RecommendationSelectionService selectionService;
    private final RecommendationReasonService reasonService;
    private final PreferenceService preferenceService;
    private final RecommendationRegionService regionService;
    private final OpportunityCategoryRepository categoryRepository;

    /** 요청당 한 번 정한 날짜를 선정과 이유 생성에 동일하게 전달한다. */
    @Transactional(readOnly = true)
    public List<RecommendationItemDto> recommend(Long userId) {
        return recommend(userId, LocalDate.now(KST));
    }

    List<RecommendationItemDto> recommend(Long userId, LocalDate today) {
        List<RecommendationEligibleCandidate> selected = selectionService.select(userId, today);
        if (selected.isEmpty()) {
            return List.of();
        }
        List<Long> opportunityIds = selected.stream().map(item -> item.candidate().opportunityId()).toList();
        Set<Long> savedIds = preferenceService.getSavedOpportunityIds(userId, opportunityIds);
        Map<Long, List<OpportunityDetailResponseDto.RegionItem>> regions = regionService.getRegions(opportunityIds);
        // 최종 선정된 청년정책의 분류 행만 한 번에 읽는다 (선정 SQL에는 쓰지 않는다)
        Map<Long, List<OpportunityCategoryItem>> categories = categoryRepository.findCurrentItemsByOpportunityIds(
                selected.stream().map(RecommendationEligibleCandidate::candidate)
                        .filter(candidate -> candidate.type() == OpportunityType.YOUTH_POLICY)
                        .map(RecommendationCandidate::opportunityId).toList());
        return selected.stream().map(item -> toItem(item, savedIds, regions, categories, today)).toList();
    }

    private RecommendationItemDto toItem(RecommendationEligibleCandidate item, Set<Long> savedIds,
                                         Map<Long, List<OpportunityDetailResponseDto.RegionItem>> regions,
                                         Map<Long, List<OpportunityCategoryItem>> categories,
                                         LocalDate today) {
        RecommendationCandidate candidate = item.candidate();
        return new RecommendationItemDto(candidate.opportunityId(), candidate.type(), candidate.title(),
                candidate.category(), candidate.subCategory(), candidate.detailCategory(),
                categories.getOrDefault(candidate.opportunityId(), List.of()),
                regions.getOrDefault(candidate.opportunityId(), List.of()),
                candidate.applicationStatus(), item.eligibilityStatus(), candidate.applicationEndDate(),
                candidate.trainingStartDate(), candidate.createdAt(), savedIds.contains(candidate.opportunityId()),
                reasonService.create(item, today));
    }
}
