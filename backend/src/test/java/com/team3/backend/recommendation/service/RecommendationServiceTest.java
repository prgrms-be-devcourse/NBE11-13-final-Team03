package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.repository.OpportunityCategoryRepository;
import com.team3.backend.opportunity.dto.OpportunityCategoryItem;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 선정 순서와 저장 상태를 재판정 없이 응답에 연결하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Mock private RecommendationSelectionService selectionService;
    @Mock private PreferenceService preferenceService;
    @Mock private RecommendationRegionService regionService;
    @Mock private OpportunityCategoryRepository categoryRepository;

    @Test
    void preservesSelectionOrderAndReadsSavedIdsOnceWithSameDateForReasons() {
        var first = item(9L, TODAY.plusDays(14));
        var second = item(2L, TODAY.plusDays(1));
        when(selectionService.select(7L, TODAY)).thenReturn(List.of(first, second));
        when(preferenceService.getSavedOpportunityIds(7L, List.of(9L, 2L))).thenReturn(Set.of(2L));
        var seoul = new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "SPECIFIC",
                "1100000000", "서울", ValidationStatus.VALID);
        when(regionService.getRegions(List.of(9L, 2L))).thenReturn(Map.of(9L, List.of(seoul)));
        var categories = List.of(new OpportunityCategoryItem("일자리", "취업"),
                new OpportunityCategoryItem("교육", "미래역량강화"));
        when(categoryRepository.findCurrentItemsByOpportunityIds(List.of(9L, 2L))).thenReturn(Map.of(9L, categories));
        var service = service();

        var response = service.recommend(7L, TODAY);

        assertEquals(List.of(9L, 2L), response.stream().map(item -> item.opportunityId()).toList());
        assertEquals(List.of(false, true), response.stream().map(item -> item.saved()).toList());
        assertEquals(List.of(seoul), response.getFirst().regions());
        assertEquals(List.of(), response.getLast().regions());
        assertEquals(categories, response.getFirst().categories());
        assertEquals(List.of(), response.getLast().categories());
        assertEquals("마감 D-14", response.getFirst().recommendationReasons().badges().getLast());
        assertEquals("마감 D-1", response.getLast().recommendationReasons().badges().getLast());
        verify(preferenceService).getSavedOpportunityIds(7L, List.of(9L, 2L));
        verify(regionService).getRegions(List.of(9L, 2L));
        verify(categoryRepository).findCurrentItemsByOpportunityIds(List.of(9L, 2L));
    }

    @Test
    void readsCategoriesOnlyForSelectedYouthPolicies() {
        var policy = item(9L, TODAY.plusDays(3));
        var training = new RecommendationEligibleCandidate(new RecommendationCandidate(4L,
                OpportunityType.VOCATIONAL_TRAINING, "훈련", "교육･직업훈련", "정보통신", "정보기술", null, null,
                TODAY.plusDays(5), LocalDateTime.of(2026, 10, 1, 12, 0)), InterestMatchLevel.HIGH,
                EligibilityStatus.ELIGIBLE);
        when(selectionService.select(7L, TODAY)).thenReturn(List.of(training, policy));
        when(categoryRepository.findCurrentItemsByOpportunityIds(List.of(9L))).thenReturn(Map.of());

        var response = service().recommend(7L, TODAY);

        assertEquals(List.of(List.of(), List.of()), response.stream().map(item -> item.categories()).toList());
        assertEquals("교육･직업훈련", response.getFirst().category());
        verify(categoryRepository).findCurrentItemsByOpportunityIds(List.of(9L));
    }

    @Test
    void emptySelectionReturnsEmptyListWithoutSavedLookup() {
        when(selectionService.select(7L, TODAY)).thenReturn(List.of());

        assertEquals(List.of(), service().recommend(7L, TODAY));
        verifyNoInteractions(preferenceService);
        verifyNoInteractions(regionService);
        verifyNoInteractions(categoryRepository);
    }

    private RecommendationService service() {
        return new RecommendationService(selectionService, new RecommendationReasonService(), preferenceService,
                regionService, categoryRepository);
    }

    private static RecommendationEligibleCandidate item(Long id, LocalDate deadline) {
        return new RecommendationEligibleCandidate(new RecommendationCandidate(id, OpportunityType.YOUTH_POLICY,
                "정책", "일자리", "취업", null, null, deadline, null,
                LocalDateTime.of(2026, 10, 1, 12, 0)), InterestMatchLevel.HIGH, EligibilityStatus.ELIGIBLE);
    }
}
