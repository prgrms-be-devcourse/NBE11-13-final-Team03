package com.team3.backend.recommendation.service;

import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.interest.dto.UserInterestResponse;
import com.team3.backend.interest.service.UserInterestService;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 사용자의 관심 분야를 한 번 조회하여 추천 후보 배치에서 재사용할 매칭 기준을 만든다. */
@Service
@RequiredArgsConstructor
public class RecommendationInterestMatcher {

    private final UserInterestService userInterestService;

    /** 추천 요청 시작 시 호출하고 반환된 기준을 모든 후보 배치에 재사용한다. */
    public MatchContext load(Long userId) {
        List<UserInterestResponse> interests = userInterestService.getInterests(userId).interests();
        Map<InterestType, Set<String>> codesByType = new EnumMap<>(InterestType.class);
        for (UserInterestResponse interest : interests) {
            codesByType.computeIfAbsent(interest.type(), ignored -> new HashSet<>()).add(interest.code());
        }
        return new MatchContext(interests.isEmpty(), codesByType);
    }

    /** 분류명 정확 일치를 판정하며 불일치 공고는 빈 결과로 나타낸다. */
    public static final class MatchContext {

        private final boolean unrestricted;
        private final Map<InterestType, Set<String>> codesByType;

        private MatchContext(boolean unrestricted, Map<InterestType, Set<String>> codesByType) {
            this.unrestricted = unrestricted;
            Map<InterestType, Set<String>> copy = new EnumMap<>(InterestType.class);
            codesByType.forEach((type, codes) -> copy.put(type, Set.copyOf(codes)));
            this.codesByType = Map.copyOf(copy);
        }

        /** 현재 하위 분류명이 상위 분류 간 유일하다는 전제로 청년정책 중분류와 NCS 2차분류를 우선한다. */
        public Optional<InterestMatchLevel> match(RecommendationCandidate candidate) {
            if (unrestricted) {
                return Optional.of(InterestMatchLevel.UNRESTRICTED);
            }
            if (candidate.type() == OpportunityType.YOUTH_POLICY) {
                if (matches(InterestType.SUB_CATEGORY, candidate.subCategory())) {
                    return Optional.of(InterestMatchLevel.HIGH);
                }
                if (matches(InterestType.CATEGORY, candidate.category())) {
                    return Optional.of(InterestMatchLevel.MEDIUM);
                }
            } else if (candidate.type() == OpportunityType.VOCATIONAL_TRAINING) {
                if (matches(InterestType.DETAIL_CATEGORY, candidate.detailCategory())) {
                    return Optional.of(InterestMatchLevel.HIGH);
                }
                if (matches(InterestType.SUB_CATEGORY, candidate.subCategory())) {
                    return Optional.of(InterestMatchLevel.MEDIUM);
                }
            }
            return Optional.empty();
        }

        private boolean matches(InterestType type, String category) {
            return category != null && !category.isBlank()
                    && codesByType.getOrDefault(type, Set.of()).contains(category);
        }
    }
}
