package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 추천 선정의 전체 후보 순회 참조 구현 (DB 그룹별 상위 후보 조회로 바꾸기 전의 운영 코드).
 * 후보를 ID 커서 배치로 끝까지 읽어 숨김·관심 적합도·최신 신청 가능성을 Java에서 적용한다.
 * 그룹 유지·혼합 규칙은 운영 코드(RecommendationSelectionService.TopCandidates)를 그대로 쓰고,
 * 운영의 SQL 상위 후보 선정이 이 구현과 같은 결과를 내는지 비교하는 정답으로 쓴다.
 */
public class RecommendationFullScanReference {

    private final EligibilityProfileService profileService;
    private final RecommendationCandidateService candidateService;
    private final RecommendationInterestMatcher interestMatcher;
    private final RecommendationEligibilityService eligibilityService;
    private final PreferenceService preferenceService;

    public RecommendationFullScanReference(EligibilityProfileService profileService,
                                           RecommendationCandidateService candidateService,
                                           RecommendationInterestMatcher interestMatcher,
                                           RecommendationEligibilityService eligibilityService,
                                           PreferenceService preferenceService) {
        this.profileService = profileService;
        this.candidateService = candidateService;
        this.interestMatcher = interestMatcher;
        this.eligibilityService = eligibilityService;
        this.preferenceService = preferenceService;
    }

    /** 프로필을 먼저 확인한 뒤 커서가 끝날 때까지 모든 후보를 평가한다. 결과는 배치 크기와 무관하다. */
    public List<RecommendationEligibleCandidate> select(Long userId, int batchSize, LocalDate today) {
        if (batchSize < 1 || today == null) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
        profileService.get(userId);
        var matchContext = interestMatcher.load(userId);
        // 숨김은 배치마다 조회하지 않고 요청 시작 시 한 번 읽어 모든 배치에 재사용한다.
        Set<Long> hidden = preferenceService.getHiddenOpportunityIds(userId);
        RecommendationSelectionService.TopCandidates top = new RecommendationSelectionService.TopCandidates(today);
        long cursor = 0L;

        while (true) {
            List<RecommendationCandidate> batch = candidateService.getCandidatesAfter(cursor, batchSize, today);
            if (batch.isEmpty()) {
                break;
            }
            cursor = batch.getLast().opportunityId();
            List<RecommendationCandidate> visible = batch.stream()
                    .filter(candidate -> !hidden.contains(candidate.opportunityId()))
                    .toList();
            eligibilityService.filterEligible(userId, visible, matchContext, today).forEach(top::add);
        }
        return top.choose();
    }
}
