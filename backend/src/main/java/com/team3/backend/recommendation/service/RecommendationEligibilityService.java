package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 관심 일치 후보에 저장된 최신 신청 가능성 상태를 배치 단위로 연결한다. */
@Service
@RequiredArgsConstructor
public class RecommendationEligibilityService {

    private final EligibilityProvider eligibilityProvider;

    /**
     * 운영 미사용: 기존 전체 후보 순회 방식의 동등성 검증 및 성능 측정에만 사용한다 (운영 추천은 RecommendationCandidateRepository.findTopPerGroup이 DB에서 적용).
     * 후보 순회 시작 시 정한 KST 날짜와 관심 기준을 모든 배치에 동일하게 전달한다.
     */
    @Transactional(readOnly = true)
    public List<RecommendationEligibleCandidate> filterEligible(
            Long userId,
            List<RecommendationCandidate> candidates,
            RecommendationInterestMatcher.MatchContext matchContext,
            LocalDate today
    ) {
        List<InterestMatchedCandidate> matched = new ArrayList<>();
        for (RecommendationCandidate candidate : candidates) {
            matchContext.match(candidate).ifPresent(level -> matched.add(new InterestMatchedCandidate(candidate, level)));
        }
        if (matched.isEmpty()) {
            return List.of();
        }

        List<Long> opportunityIds = matched.stream().map(item -> item.candidate().opportunityId()).toList();
        // 미판정·오래된 판정·프로필 미등록은 맵에서 빠지므로 신청 가능 후보로 추정하지 않는다.
        Map<Long, EligibilityStatus> statuses = eligibilityProvider.findFreshStatuses(userId, opportunityIds, today);
        return matched.stream()
                .filter(item -> isRecommendable(statuses.get(item.candidate().opportunityId())))
                .map(item -> new RecommendationEligibleCandidate(item.candidate(), item.level(),
                        statuses.get(item.candidate().opportunityId())))
                .toList();
    }

    private static boolean isRecommendable(EligibilityStatus status) {
        return status == EligibilityStatus.ELIGIBLE || status == EligibilityStatus.POSSIBLY_ELIGIBLE;
    }

    private record InterestMatchedCandidate(RecommendationCandidate candidate, InterestMatchLevel level) {
    }
}
