package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.dto.EligibilityResponse;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.dto.OpportunitySummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.team3.backend.eligibility.exception.EligibilityErrorCode.*;

/**
 * 요청 시점 판정 (단건 판정 API, 페이지 단위 목록 판정 API). 결과를 저장하지 않는다. 기준일은 한국 시간 오늘.
 * 추천·검색처럼 판정 결과를 재사용하거나 판정 결과로 필터링하는 쪽은 저장된 판정 결과(EligibilityResultService)를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EligibilityQueryService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final int MAX_PAGE_SIZE = 100;

    private final EligibilityProfileRepository profileRepository;
    private final OpportunityRepository opportunityRepository;
    private final EligibilityEvaluator evaluator;

    public EligibilityResponse evaluate(Long userId, Long opportunityId) {
        return evaluate(userId, opportunityId, OffsetDateTime.now(KST));
    }

    public EligibilityResponse evaluate(Long userId, Long opportunityId, OffsetDateTime now) {
        EligibilityProfile profile = findProfile(userId);
        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new BusinessException(OPPORTUNITY_NOT_FOUND));
        OpportunityVersion version = opportunity.getCurrentVersion();
        if (version == null) {
            throw new BusinessException(NOT_NORMALIZED);
        }
        LocalDate today = now.atZoneSameInstant(KST).toLocalDate();
        Long versionId = version.getId();

        EligibilityEvaluator.Judgement judgement = evaluator.judge(profile, List.of(versionId), today).get(versionId);
        return EligibilityResponse.of(opportunity.getId(), versionId, judgement.evaluation(),
                judgement.applicationStatus(), now.atZoneSameInstant(KST).toOffsetDateTime());
    }

    public EligibilityListResponse evaluateAll(Long userId, int page, int size) {
        return evaluateAll(userId, page, size, OffsetDateTime.now(KST));
    }

    /**
     * 정규화된 기회를 최신순(id 내림차순)으로 페이지 단위 판정한다. 결과는 저장하지 않는다.
     * 판정 결과로 필터링한 목록은 저장된 판정 결과(EligibilityResultService)를 기준으로 조회한다.
     */
    public EligibilityListResponse evaluateAll(Long userId, int page, int size, OffsetDateTime now) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(INVALID_PAGE_REQUEST, "page=" + page + ", size=" + size);
        }
        EligibilityProfile profile = findProfile(userId);
        LocalDate today = now.atZoneSameInstant(KST).toLocalDate();

        Page<OpportunitySummaryRow> opportunities = opportunityRepository.findNormalizedSummaries(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        Map<Long, EligibilityEvaluator.Judgement> judgements = evaluator.judge(profile,
                opportunities.stream().map(OpportunitySummaryRow::versionId).toList(), today);

        List<EligibilityListResponse.Item> items = opportunities.stream()
                .map(o -> {
                    EligibilityEvaluator.Judgement judgement = judgements.get(o.versionId());
                    return new EligibilityListResponse.Item(o.opportunityId(), o.versionId(), o.opportunityType(),
                            o.title(), o.category(), judgement.evaluation().status(),
                            judgement.applicationStatus(), judgement.evaluation().reason());
                })
                .toList();

        return new EligibilityListResponse(items, opportunities.getNumber(), opportunities.getSize(),
                opportunities.getTotalElements(), opportunities.getTotalPages(), opportunities.hasNext(),
                now.atZoneSameInstant(KST).toOffsetDateTime());
    }

    private EligibilityProfile findProfile(Long userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(PROFILE_NOT_FOUND));
    }
}
