package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.dto.UserInterestResponse;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.service.UserInterestService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 추천 그룹 배분과 전체 커서 순회 및 날짜 우선순위를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationSelectionServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Mock private EligibilityProfileService profileService;
    @Mock private RecommendationCandidateService candidateService;
    @Mock private UserInterestService userInterestService;
    @Mock private EligibilityProvider eligibilityProvider;
    @Mock private PreferenceService preferenceService;

    /** 그룹·정렬·혼합 규칙은 운영 RecommendationSelectionService.TopCandidates를 그대로 쓰는 전체 순회 참조 구현으로 검증한다 */
    private RecommendationFullScanReference service;

    @BeforeEach
    void setUp() {
        var matcher = new RecommendationInterestMatcher(userInterestService);
        var eligibility = new RecommendationEligibilityService(eligibilityProvider);
        service = new RecommendationFullScanReference(profileService, candidateService, matcher,
                eligibility, preferenceService);
    }

    @Test
    void aGroupAlwaysWinsEvenWhenBetterDatesAppearInOtherGroups() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 30, 20), policy(2, "medium", 1, 20),
                policy(3, "high", 1, 20), policy(4, "medium", 2, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());

        assertEquals(List.of(3L, 1L, 2L, 4L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void fiveAGroupCandidatesExcludeEveryLowerGroupAndLaterBetterAReplacesEarlier() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 14, 20), policy(2, "high", 13, 20),
                policy(3, "high", 12, 20), policy(4, "high", 11, 20),
                policy(5, "high", 10, 20), policy(6, "high", 0, 20),
                policy(7, "medium", 1, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.ELIGIBLE,
                5L, EligibilityStatus.ELIGIBLE, 6L, EligibilityStatus.ELIGIBLE,
                7L, EligibilityStatus.ELIGIBLE), Set.of());

        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), ids(service.select(USER_ID, 2, TODAY)));
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), ids(service.select(USER_ID, 4, TODAY)));
        verify(candidateService).getCandidatesAfter(6L, 2, TODAY);
    }

    @Test
    void bTakesLargerShareWhileCReceivesOneWhenBothAreAvailable() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "medium", 8, 20), policy(2, "medium", 7, 20),
                policy(3, "medium", 6, 20), policy(4, "medium", 5, 20),
                policy(5, "medium", 4, 20), policy(6, "high", 2, 20),
                policy(7, "high", 1, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.ELIGIBLE,
                5L, EligibilityStatus.ELIGIBLE, 6L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                7L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());

        assertEquals(List.of(5L, 4L, 3L, 2L, 7L), ids(service.select(USER_ID, 3, TODAY)));
    }

    @Test
    void twoAGroupCandidatesLeaveTwoSeatsForBAndOneForC() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 2, 20), policy(2, "high", 1, 20),
                policy(3, "medium", 3, 20), policy(4, "medium", 2, 20),
                policy(5, "medium", 1, 20), policy(6, "high", 4, 20),
                policy(7, "high", 3, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.ELIGIBLE,
                5L, EligibilityStatus.ELIGIBLE, 6L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                7L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());

        assertEquals(List.of(2L, 1L, 5L, 4L, 7L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void shortageInEitherMiddleGroupUsesTheOtherBeforeD() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 1, 20), policy(2, "medium", 2, 20),
                policy(3, "high", 3, 20), policy(4, "high", 4, 20),
                policy(5, "high", 5, 20), policy(6, "medium", 1, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.POSSIBLY_ELIGIBLE, 4L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                5L, EligibilityStatus.POSSIBLY_ELIGIBLE, 6L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(service.select(USER_ID, 2, TODAY)));

        List<RecommendationCandidate> cShortage = List.of(
                policy(1, "high", 1, 20), policy(2, "medium", 2, 20),
                policy(3, "medium", 3, 20), policy(4, "medium", 4, 20),
                policy(5, "medium", 5, 20), policy(6, "high", 1, 20));
        setup(cShortage, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.ELIGIBLE,
                5L, EligibilityStatus.ELIGIBLE, 6L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());
        assertEquals(List.of(1L, 2L, 3L, 4L, 6L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void lastSeatPrefersBAndShortageFallsBackToCThenD() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 4, 20), policy(2, "high", 3, 20),
                policy(3, "high", 2, 20), policy(4, "high", 1, 20),
                policy(5, "medium", 10, 20), policy(6, "high", 0, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.ELIGIBLE,
                5L, EligibilityStatus.ELIGIBLE, 6L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());
        assertEquals(List.of(4L, 3L, 2L, 1L, 5L), ids(service.select(USER_ID, 2, TODAY)));

        List<RecommendationCandidate> shortage = List.of(
                policy(1, "medium", 5, 20), policy(2, "high", 4, 20),
                policy(3, "high", 3, 20), policy(4, "medium", 2, 20));
        setup(shortage, Map.of(1L, EligibilityStatus.ELIGIBLE,
                2L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                3L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                4L, EligibilityStatus.POSSIBLY_ELIGIBLE), Set.of());
        assertEquals(List.of(1L, 3L, 2L, 4L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void unrestrictedUsesEligibilityFirstAndHiddenCandidatesNeverReachProvider() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "other", 1, 20), policy(2, "other", 2, 20),
                policy(3, "other", 3, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                2L, EligibilityStatus.ELIGIBLE, 3L, EligibilityStatus.ELIGIBLE), Set.of(3L), true);

        List<RecommendationEligibleCandidate> result = service.select(USER_ID, 3, TODAY);

        assertEquals(List.of(2L, 1L), ids(result));
        assertEquals(InterestMatchLevel.UNRESTRICTED, result.getFirst().interestMatchLevel());
        verify(eligibilityProvider).findFreshStatuses(USER_ID, List.of(1L, 2L), TODAY);
    }

    @Test
    void hiddenIdsAreLoadedOnceAndAppliedToEveryBatch() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "other", 1, 20), policy(2, "other", 2, 20),
                policy(3, "other", 3, 20), policy(4, "other", 4, 20));
        setup(candidates, Map.of(1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.ELIGIBLE), Set.of(1L, 4L), true);

        assertEquals(List.of(2L, 3L), ids(service.select(USER_ID, 1, TODAY)));
        verify(preferenceService, times(1)).getHiddenOpportunityIds(USER_ID);
        verify(eligibilityProvider, never()).findFreshStatuses(USER_ID, List.of(1L), TODAY);
        verify(eligibilityProvider, never()).findFreshStatuses(USER_ID, List.of(4L), TODAY);
    }

    @Test
    void policyDeadlineAndNewBoundariesAreStable() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 15, 8), policy(2, "high", 14, 8),
                policy(3, "high", 1, 8), policy(4, "high", 15, 7),
                policy(5, "high", 15, 8), policy(6, "high", -1, 0));
        setup(candidates, eligible(candidates), Set.of());

        assertEquals(List.of(3L, 2L, 4L, 6L, 5L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void equalDatesUseOpportunityIdDescending() {
        List<RecommendationCandidate> candidates = List.of(
                policy(1, "high", 7, 20), policy(2, "high", 7, 20),
                policy(3, "high", 7, 20));
        setup(candidates, eligible(candidates), Set.of());

        assertEquals(List.of(3L, 2L, 1L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void trainingStartWindowAndAlwaysOpenPolicyUseDistinctDates() {
        List<RecommendationCandidate> candidates = List.of(
                training(1, 22), training(2, 21), training(3, 7),
                policy(4, "high", -1, 8), training(5, 8));
        setup(candidates, eligible(candidates), Set.of());

        assertEquals(List.of(3L, 5L, 2L, 1L, 4L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void mixedTypesUseUrgencyThenActualDateThenIdWithoutTrainingDeadlineSubstitution() {
        List<RecommendationCandidate> candidates = List.of(
                training(1, 21), policy(2, "high", 14, 20),
                training(3, 7), policy(4, "high", 7, 20),
                policy(5, "high", -1, 0));
        setup(candidates, eligible(candidates), Set.of());

        assertEquals(List.of(4L, 3L, 2L, 1L, 5L), ids(service.select(USER_ID, 2, TODAY)));
    }

    @Test
    void shuffledFixtureAndDifferentBatchSizesProduceTheSameFinalOrder() {
        List<RecommendationCandidate> candidates = List.of(
                policy(6, "high", 1, 20), policy(3, "medium", 2, 20),
                policy(1, "high", 4, 20), policy(5, "medium", 1, 20),
                policy(2, "medium", 3, 20), policy(4, "high", 2, 20));
        Map<Long, EligibilityStatus> statuses = Map.of(
                1L, EligibilityStatus.ELIGIBLE, 2L, EligibilityStatus.ELIGIBLE,
                3L, EligibilityStatus.ELIGIBLE, 4L, EligibilityStatus.POSSIBLY_ELIGIBLE,
                5L, EligibilityStatus.ELIGIBLE, 6L, EligibilityStatus.POSSIBLY_ELIGIBLE);
        setup(candidates, statuses, Set.of());

        List<Long> first = ids(service.select(USER_ID, 1, TODAY));
        assertEquals(List.of(1L, 5L, 3L, 2L, 6L), first);
        assertEquals(first, ids(service.select(USER_ID, 4, TODAY)));
    }

    @Test
    void profileIsRequiredBeforeAnyCandidateQuery() {
        doThrow(new BusinessException(EligibilityErrorCode.PROFILE_NOT_FOUND))
                .when(profileService).get(USER_ID);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.select(USER_ID, 2, TODAY));
        assertEquals(EligibilityErrorCode.PROFILE_NOT_FOUND, exception.getErrorCode());
        verify(profileService).get(USER_ID);
    }

    private void setup(List<RecommendationCandidate> candidates,
                       Map<Long, EligibilityStatus> statuses, Set<Long> hidden) {
        setup(candidates, statuses, hidden, false);
    }

    private void setup(List<RecommendationCandidate> candidates,
                       Map<Long, EligibilityStatus> statuses, Set<Long> hidden, boolean unrestricted) {
        List<UserInterestResponse> interests = unrestricted ? List.of() : List.of(
                new UserInterestResponse(InterestType.SUB_CATEGORY, "high"),
                new UserInterestResponse(InterestType.DETAIL_CATEGORY, "high"),
                new UserInterestResponse(InterestType.CATEGORY, "medium"));
        when(userInterestService.getInterests(USER_ID)).thenReturn(new UserInterestsResponse(interests));
        when(candidateService.getCandidatesAfter(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), eq(TODAY))).thenAnswer(invocation -> {
                    long afterId = invocation.getArgument(0);
                    int size = invocation.getArgument(1);
                    return candidates.stream().filter(candidate -> candidate.opportunityId() > afterId)
                            .sorted(java.util.Comparator.comparing(RecommendationCandidate::opportunityId))
                            .limit(size).toList();
                });
        when(preferenceService.getHiddenOpportunityIds(USER_ID)).thenReturn(hidden);
        when(eligibilityProvider.findFreshStatuses(eq(USER_ID), anyList(), eq(TODAY)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(1);
                    return statuses.entrySet().stream().filter(entry -> ids.contains(entry.getKey()))
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                });
    }

    private static Map<Long, EligibilityStatus> eligible(List<RecommendationCandidate> candidates) {
        return candidates.stream().collect(java.util.stream.Collectors.toMap(
                RecommendationCandidate::opportunityId, ignored -> EligibilityStatus.ELIGIBLE));
    }

    private static List<Long> ids(List<RecommendationEligibleCandidate> candidates) {
        return candidates.stream().map(item -> item.candidate().opportunityId()).toList();
    }

    private static RecommendationCandidate policy(long id, String level, int deadlineDays, int ageDays) {
        return new RecommendationCandidate(id, OpportunityType.YOUTH_POLICY, "정책", level.equals("high") ? "기타" : "medium",
                level.equals("high") ? "high" : null, null, null,
                deadlineDays < 0 ? null : TODAY.plusDays(deadlineDays), null,
                LocalDateTime.of(TODAY.minusDays(ageDays), java.time.LocalTime.NOON));
    }

    private static RecommendationCandidate training(long id, int startDays) {
        return new RecommendationCandidate(id, OpportunityType.VOCATIONAL_TRAINING, "훈련", "교육･직업훈련",
                "기타", "high", null, null, TODAY.plusDays(startDays),
                LocalDateTime.of(TODAY.minusDays(30), java.time.LocalTime.NOON));
    }
}
