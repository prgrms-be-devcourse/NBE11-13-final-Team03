package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.dto.UserInterestResponse;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.service.UserInterestService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 추천 배치와 저장된 최신 판정 상태의 연결 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationEligibilityServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Mock
    private EligibilityProvider eligibilityProvider;

    @Mock
    private UserInterestService userInterestService;

    @Test
    void keepsOnlyEligibleStatusesByIdWithoutChangingCandidateOrder() {
        var context = context(List.of(new UserInterestResponse(InterestType.CATEGORY, "일자리")));
        List<RecommendationCandidate> candidates = List.of(
                candidate(1L, "일자리"), candidate(2L, "일자리"), candidate(3L, "일자리"),
                candidate(4L, "일자리"), candidate(5L, "일자리"));
        when(eligibilityProvider.findFreshStatuses(USER_ID, List.of(1L, 2L, 3L, 4L, 5L), TODAY))
                .thenReturn(Map.of(4L, EligibilityStatus.UNKNOWN,
                        2L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                        3L, EligibilityStatus.NOT_ELIGIBLE,
                        1L, EligibilityStatus.ELIGIBLE));

        List<RecommendationEligibleCandidate> result = service().filterEligible(USER_ID, candidates, context, TODAY);

        assertEquals(List.of(1L, 2L), result.stream().map(item -> item.candidate().opportunityId()).toList());
        assertEquals(List.of(EligibilityStatus.ELIGIBLE, EligibilityStatus.POSSIBLY_ELIGIBLE),
                result.stream().map(RecommendationEligibleCandidate::eligibilityStatus).toList());
        assertEquals(List.of(InterestMatchLevel.MEDIUM, InterestMatchLevel.MEDIUM),
                result.stream().map(RecommendationEligibleCandidate::interestMatchLevel).toList());
        verify(eligibilityProvider, times(1))
                .findFreshStatuses(USER_ID, List.of(1L, 2L, 3L, 4L, 5L), TODAY);
    }

    @Test
    void excludesInterestMismatchBeforeEligibilityLookupAndRetainsHighMatch() {
        var context = context(List.of(new UserInterestResponse(InterestType.SUB_CATEGORY, "취업지원")));
        RecommendationCandidate matched = new RecommendationCandidate(1L, OpportunityType.YOUTH_POLICY,
                "공고", "일자리", "취업지원", null, null, null, null, null);
        when(eligibilityProvider.findFreshStatuses(USER_ID, List.of(1L), TODAY))
                .thenReturn(Map.of(1L, EligibilityStatus.ELIGIBLE));

        List<RecommendationEligibleCandidate> result = service().filterEligible(USER_ID,
                List.of(matched, candidate(2L, "주거")), context, TODAY);

        assertEquals(List.of(1L), result.stream().map(item -> item.candidate().opportunityId()).toList());
        assertEquals(InterestMatchLevel.HIGH, result.getFirst().interestMatchLevel());
        verify(eligibilityProvider).findFreshStatuses(USER_ID, List.of(1L), TODAY);
    }

    @Test
    void skipsProviderWhenNoCandidatesMatch() {
        var context = context(List.of(new UserInterestResponse(InterestType.CATEGORY, "일자리")));

        assertEquals(List.of(), service().filterEligible(USER_ID, List.of(candidate(1L, "주거")), context, TODAY));
        assertEquals(List.of(), service().filterEligible(USER_ID, List.of(), context, TODAY));
        verifyNoInteractions(eligibilityProvider);
    }

    @Test
    void noInterestsAllowsAllButMissingOrStaleResultsAreExcluded() {
        var context = context(List.of());
        when(eligibilityProvider.findFreshStatuses(USER_ID, List.of(1L, 2L), TODAY))
                .thenReturn(Map.of(2L, EligibilityStatus.ELIGIBLE));

        List<RecommendationEligibleCandidate> result = service().filterEligible(USER_ID,
                List.of(candidate(1L, "주거"), candidate(2L, "일자리")), context, TODAY);

        assertEquals(List.of(2L), result.stream().map(item -> item.candidate().opportunityId()).toList());
        assertEquals(InterestMatchLevel.UNRESTRICTED, result.getFirst().interestMatchLevel());
    }

    @Test
    void missingProfileReturnsEmptyWithoutGrantingEligibility() {
        var context = context(List.of());
        when(eligibilityProvider.findFreshStatuses(USER_ID, List.of(1L), TODAY)).thenReturn(Map.of());

        assertEquals(List.of(), service().filterEligible(USER_ID, List.of(candidate(1L, "일자리")), context, TODAY));
    }

    @Test
    void reusesInterestContextAndTodayAcrossCandidateBatches() {
        var context = context(List.of(new UserInterestResponse(InterestType.CATEGORY, "일자리")));
        when(eligibilityProvider.findFreshStatuses(USER_ID, List.of(1L), TODAY))
                .thenReturn(Map.of(1L, EligibilityStatus.ELIGIBLE));
        when(eligibilityProvider.findFreshStatuses(USER_ID, List.of(2L), TODAY))
                .thenReturn(Map.of(2L, EligibilityStatus.POSSIBLY_ELIGIBLE));

        assertEquals(1, service().filterEligible(USER_ID, List.of(candidate(1L, "일자리")), context, TODAY).size());
        assertEquals(1, service().filterEligible(USER_ID, List.of(candidate(2L, "일자리")), context, TODAY).size());
        verify(userInterestService, times(1)).getInterests(USER_ID);
        verify(eligibilityProvider).findFreshStatuses(USER_ID, List.of(1L), TODAY);
        verify(eligibilityProvider).findFreshStatuses(USER_ID, List.of(2L), TODAY);
    }

    private RecommendationInterestMatcher.MatchContext context(List<UserInterestResponse> interests) {
        when(userInterestService.getInterests(USER_ID)).thenReturn(new UserInterestsResponse(interests));
        return new RecommendationInterestMatcher(userInterestService).load(USER_ID);
    }

    private RecommendationEligibilityService service() {
        return new RecommendationEligibilityService(eligibilityProvider);
    }

    private static RecommendationCandidate candidate(Long id, String category) {
        return new RecommendationCandidate(id, OpportunityType.YOUTH_POLICY, "공고", category, null, null,
                null, null, null, null);
    }
}
