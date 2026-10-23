package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 판정 상태와 유형별 날짜 경계에서 추천 이유가 과장되지 않는지 검증한다. */
class RecommendationReasonServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private final RecommendationReasonService service = new RecommendationReasonService();

    @Test
    void eligibleHighPolicyUsesDeadlineAtD14AndNoMoreThanThreeBadges() {
        var reasons = service.create(item(OpportunityType.YOUTH_POLICY, EligibilityStatus.ELIGIBLE,
                InterestMatchLevel.HIGH, 14, 0, 0), TODAY);

        assertEquals(java.util.List.of("신청 조건 충족", "관심 분야 일치", "마감 D-14"), reasons.badges());
        assertTrue(reasons.description().contains("신청 마감까지 14일"));
        assertFalse(reasons.badges().contains("신규 공고"));
    }

    @Test
    void possiblyEligibleMediumNeedsConfirmationAndDeadlineOutsideWindowIsNotUrgent() {
        var reasons = service.create(item(OpportunityType.YOUTH_POLICY, EligibilityStatus.POSSIBLY_ELIGIBLE,
                InterestMatchLevel.MEDIUM, 15, 8, 0), TODAY);

        assertEquals(java.util.List.of("신청 조건 확인 필요", "관심 분야 관련"), reasons.badges());
        assertTrue(reasons.description().contains("추가로 확인해야"));
        assertFalse(reasons.description().contains("충족합니다"));
    }

    @Test
    void unrestrictedAlwaysOpenHasNoInterestOrDeadlineAndNewBoundaryIsInclusive() {
        var newReasons = service.create(item(OpportunityType.YOUTH_POLICY, EligibilityStatus.ELIGIBLE,
                InterestMatchLevel.UNRESTRICTED, -1, 7, 0), TODAY);
        var oldReasons = service.create(item(OpportunityType.YOUTH_POLICY, EligibilityStatus.ELIGIBLE,
                InterestMatchLevel.UNRESTRICTED, -1, 8, 0), TODAY);

        assertEquals(java.util.List.of("신청 조건 충족", "신규 공고"), newReasons.badges());
        assertTrue(newReasons.description().contains("Y:ON에 최근 등록"));
        assertEquals(java.util.List.of("신청 조건 충족"), oldReasons.badges());
    }

    @Test
    void trainingUsesStartDateNotApplicationDeadlineAtD7AndD21() {
        for (int days : java.util.List.of(7, 21)) {
            var reasons = service.create(item(OpportunityType.VOCATIONAL_TRAINING, EligibilityStatus.ELIGIBLE,
                    InterestMatchLevel.HIGH, -1, 20, days), TODAY);
            assertTrue(reasons.badges().contains("교육 시작 D-" + days));
            assertTrue(reasons.description().contains("교육 시작까지 " + days + "일"));
            assertFalse(reasons.description().contains("신청 마감"));
        }
        var outside = service.create(item(OpportunityType.VOCATIONAL_TRAINING, EligibilityStatus.ELIGIBLE,
                InterestMatchLevel.HIGH, -1, 20, 22), TODAY);
        assertEquals(java.util.List.of("신청 조건 충족", "관심 분야 일치"), outside.badges());
    }

    private static RecommendationEligibleCandidate item(OpportunityType type, EligibilityStatus status,
                                                        InterestMatchLevel match, int deadlineDays,
                                                        int ageDays, int startDays) {
        RecommendationCandidate candidate = new RecommendationCandidate(1L, type, "공고", "분류", "중분류",
                null, null, deadlineDays < 0 ? null : TODAY.plusDays(deadlineDays),
                type == OpportunityType.VOCATIONAL_TRAINING ? TODAY.plusDays(startDays) : null,
                LocalDateTime.of(TODAY.minusDays(ageDays), LocalTime.NOON));
        return new RecommendationEligibleCandidate(candidate, match, status);
    }
}
