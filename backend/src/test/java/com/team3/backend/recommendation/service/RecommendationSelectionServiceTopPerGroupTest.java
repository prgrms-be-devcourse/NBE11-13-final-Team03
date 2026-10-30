package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.dto.UserInterestResponse;
import com.team3.backend.interest.dto.UserInterestsResponse;
import com.team3.backend.interest.service.UserInterestService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.status.ApplicationStatus;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository.CandidateRow;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository.TopCandidateRow;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 그룹별 상위 후보를 받아 기존 그룹 혼합·우선순위로 최종 후보를 고르는 운영 경로를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationSelectionServiceTopPerGroupTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Mock private EligibilityProfileService profileService;
    @Mock private RecommendationCandidateService candidateService;
    @Mock private UserInterestService userInterestService;
    @Mock private RecommendationCandidateRepository candidateRepository;

    private RecommendationSelectionService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationSelectionService(profileService, candidateService,
                new RecommendationInterestMatcher(userInterestService), candidateRepository);
    }

    @Test
    void rowsInAnyOrderAreGroupedAndMixedByExistingRulesWithInterestCodesAndGroupLimit() {
        when(userInterestService.getInterests(USER_ID)).thenReturn(new UserInterestsResponse(List.of(
                new UserInterestResponse(InterestType.SUB_CATEGORY, "high"),
                new UserInterestResponse(InterestType.CATEGORY, "medium"),
                new UserInterestResponse(InterestType.DETAIL_CATEGORY, "detail"))));
        // DB가 그룹·순서를 보장하지 않아도 Java 우선순위로 다시 정렬한다: B 2건(마감 1·3일), C 2건(마감 2·4일), A 1건(마감 30일)
        List<TopCandidateRow> rows = List.of(
                row(5, InterestMatchLevel.HIGH, EligibilityStatus.POSSIBLY_ELIGIBLE),
                row(2, InterestMatchLevel.MEDIUM, EligibilityStatus.ELIGIBLE),
                row(1, InterestMatchLevel.HIGH, EligibilityStatus.ELIGIBLE),
                row(4, InterestMatchLevel.MEDIUM, EligibilityStatus.ELIGIBLE),
                row(3, InterestMatchLevel.HIGH, EligibilityStatus.POSSIBLY_ELIGIBLE));
        when(candidateRepository.findTopPerGroup(USER_ID, TODAY, false, Set.of("high"), Set.of("medium"),
                Set.of("detail"), RecommendationSelectionService.LIMIT)).thenReturn(rows);
        when(candidateService.toCandidates(any(), any())).thenAnswer(invocation -> {
            List<CandidateRow> candidateRows = invocation.getArgument(0);
            return candidateRows.stream().map(RecommendationSelectionServiceTopPerGroupTest::candidate).toList();
        });

        // A(1) → 남은 4자리 중 C 1자리 확보, B 3자리까지(2건뿐) → C 나머지
        assertEquals(List.of(1L, 2L, 4L, 3L, 5L), service.select(USER_ID, TODAY).stream()
                .map(item -> item.candidate().opportunityId()).toList());
    }

    @Test
    void missingProfileFailsBeforeQueryingCandidates() {
        doThrow(new BusinessException(EligibilityErrorCode.PROFILE_NOT_FOUND)).when(profileService).get(USER_ID);

        assertThrows(BusinessException.class, () -> service.select(USER_ID, TODAY));
        verify(candidateRepository, never()).findTopPerGroup(anyLong(), any(), anyBoolean(), any(), any(), any(), anyInt());
    }

    @Test
    void noRowsSelectNothing() {
        when(userInterestService.getInterests(USER_ID)).thenReturn(new UserInterestsResponse(List.of()));
        when(candidateRepository.findTopPerGroup(USER_ID, TODAY, true, Set.of(), Set.of(), Set.of(),
                RecommendationSelectionService.LIMIT)).thenReturn(List.of());
        when(candidateService.toCandidates(List.of(), TODAY)).thenReturn(List.of());

        assertEquals(List.of(), service.select(USER_ID, TODAY));
    }

    /** 마감일 = ID일 뒤 (모두 D-14 이내라 같은 구간, 날짜순) */
    private static TopCandidateRow row(long id, InterestMatchLevel level, EligibilityStatus status) {
        return new TopCandidateRow(new CandidateRow(id, id * 10, OpportunityType.YOUTH_POLICY, "정책 " + id,
                "medium", "high", null, TODAY.minusDays(30).atStartOfDay()), level, status);
    }

    private static RecommendationCandidate candidate(CandidateRow row) {
        long days = row.opportunityId() == 1 ? 30 : row.opportunityId();
        return new RecommendationCandidate(row.opportunityId(), row.type(), row.title(), row.category(),
                row.subCategory(), row.detailCategory(), ApplicationStatus.OPEN, TODAY.plusDays(days), null,
                row.createdAt());
    }
}
