package com.team3.backend.recommendation.service;

import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.dto.UserInterestResponse;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.service.UserInterestService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 관심 분야 적합도와 배치 간 동일 기준 재사용을 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationInterestMatcherTest {

    @Mock
    private UserInterestService userInterestService;

    @Test
    void youthPolicyPrefersSubCategoryAndExcludesMismatch() {
        RecommendationInterestMatcher.MatchContext context = load(
                interest(InterestType.CATEGORY, "일자리"),
                interest(InterestType.SUB_CATEGORY, "취업지원"));

        assertEquals(Optional.of(InterestMatchLevel.HIGH),
                context.match(candidate(OpportunityType.YOUTH_POLICY, "일자리", "취업지원", null)));
        assertEquals(Optional.of(InterestMatchLevel.MEDIUM),
                context.match(candidate(OpportunityType.YOUTH_POLICY, "일자리", "창업", null)));
        assertEquals(Optional.empty(),
                context.match(candidate(OpportunityType.YOUTH_POLICY, "주거", "월세", null)));
    }

    @Test
    void trainingPrefersDetailAndIgnoresCommonCategoryAndRawNcsCode() {
        RecommendationInterestMatcher.MatchContext context = load(
                interest(InterestType.CATEGORY, "교육･직업훈련"),
                interest(InterestType.SUB_CATEGORY, "정보통신"),
                interest(InterestType.DETAIL_CATEGORY, "정보기술"),
                interest(InterestType.NCS, "20010202"));

        assertEquals(Optional.of(InterestMatchLevel.HIGH),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "정보기술")));
        assertEquals(Optional.of(InterestMatchLevel.MEDIUM),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", "통신기술")));
        assertEquals(Optional.empty(),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "경영", "경영기획")));
        assertEquals(Optional.empty(),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", null, null)));
    }

    @Test
    void detailOnlyMatchesHighAndMissingDetailCanStillMatchMedium() {
        RecommendationInterestMatcher.MatchContext context = load(
                interest(InterestType.DETAIL_CATEGORY, "정보기술"),
                interest(InterestType.SUB_CATEGORY, "정보통신"));

        assertEquals(Optional.of(InterestMatchLevel.HIGH),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "다른 분류", "정보기술")));
        assertEquals(Optional.of(InterestMatchLevel.MEDIUM),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, "교육･직업훈련", "정보통신", null)));
    }

    @Test
    void commonTrainingCategoryAndRawNcsCodeAloneDoNotMatch() {
        RecommendationInterestMatcher.MatchContext context = load(
                interest(InterestType.CATEGORY, "교육･직업훈련"),
                interest(InterestType.NCS, "20010202"));

        assertEquals(Optional.empty(), context.match(candidate(OpportunityType.VOCATIONAL_TRAINING,
                "교육･직업훈련", "정보통신", "정보기술")));
    }

    @Test
    void blankCategoryNeverMatches() {
        RecommendationInterestMatcher.MatchContext context = load(
                interest(InterestType.CATEGORY, "일자리"),
                interest(InterestType.SUB_CATEGORY, "취업지원"));

        assertEquals(Optional.empty(),
                context.match(candidate(OpportunityType.YOUTH_POLICY, null, " ", null)));
    }

    @Test
    void noInterestsAllowsEveryCandidateWithNeutralLevel() {
        RecommendationInterestMatcher.MatchContext context = load();

        assertEquals(Optional.of(InterestMatchLevel.UNRESTRICTED),
                context.match(candidate(OpportunityType.YOUTH_POLICY, null, null, null)));
        assertEquals(Optional.of(InterestMatchLevel.UNRESTRICTED),
                context.match(candidate(OpportunityType.VOCATIONAL_TRAINING, null, null, null)));
    }

    @Test
    void interestOrderAndDuplicatesDoNotChangeResultAcrossBatches() {
        UserInterestResponse category = interest(InterestType.CATEGORY, "일자리");
        UserInterestResponse subCategory = interest(InterestType.SUB_CATEGORY, "취업지원");
        RecommendationInterestMatcher.MatchContext first = load(category, subCategory, subCategory);
        RecommendationCandidate candidate = candidate(OpportunityType.YOUTH_POLICY, "일자리", "취업지원", null);

        assertEquals(Optional.of(InterestMatchLevel.HIGH), first.match(candidate));
        assertEquals(Optional.of(InterestMatchLevel.HIGH), first.match(candidate));
        verify(userInterestService, times(1)).getInterests(1L);

        when(userInterestService.getInterests(1L))
                .thenReturn(new UserInterestsResponse(List.of(subCategory, category)));
        assertEquals(first.match(candidate), new RecommendationInterestMatcher(userInterestService).load(1L).match(candidate));
    }

    private RecommendationInterestMatcher.MatchContext load(UserInterestResponse... interests) {
        when(userInterestService.getInterests(1L)).thenReturn(new UserInterestsResponse(List.of(interests)));
        return new RecommendationInterestMatcher(userInterestService).load(1L);
    }

    private static UserInterestResponse interest(InterestType type, String code) {
        return new UserInterestResponse(type, code);
    }

    private static RecommendationCandidate candidate(OpportunityType type, String category,
                                                     String subCategory, String detailCategory) {
        return new RecommendationCandidate(1L, type, "공고", category, subCategory, detailCategory,
                null, null, null, null);
    }
}
