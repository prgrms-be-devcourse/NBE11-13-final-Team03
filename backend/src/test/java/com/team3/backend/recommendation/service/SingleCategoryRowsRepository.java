package com.team3.backend.recommendation.service;

import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository;
import com.team3.backend.recommendation.dto.RecommendationCandidate;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 단위 테스트용 분류 행 조회. 등록한 청년정책 후보의 대·중분류 컬럼을 분류 행 한 개로 돌려준다 (단일 분류 공고의 정규화 결과와 같다).
 * 다중 분류 매칭은 RecommendationInterestMatcherTest와 PostgreSQL 테스트가 검증한다.
 */
final class SingleCategoryRowsRepository extends RecommendationCandidateRepository {

    private final Map<Long, List<CategoryPair>> rows = new HashMap<>();

    void register(Collection<RecommendationCandidate> candidates) {
        for (RecommendationCandidate candidate : candidates) {
            if (candidate.type() == OpportunityType.YOUTH_POLICY
                    && (candidate.category() != null || candidate.subCategory() != null)) {
                rows.put(candidate.opportunityId(),
                        List.of(new CategoryPair(candidate.category(), candidate.subCategory())));
            }
        }
    }

    @Override
    public Map<Long, List<CategoryPair>> findPolicyCategories(Collection<Long> opportunityIds) {
        Map<Long, List<CategoryPair>> result = new HashMap<>();
        for (Long id : opportunityIds) {
            List<CategoryPair> pairs = rows.get(id);
            if (pairs != null) {
                result.put(id, pairs);
            }
        }
        return result;
    }
}
