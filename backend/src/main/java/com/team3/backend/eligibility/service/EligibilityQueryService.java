package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.domain.repository.EligibilityProfileRepository;
import com.team3.backend.eligibility.dto.EligibilityListResponse;
import com.team3.backend.eligibility.dto.EligibilityResponse;
import com.team3.backend.eligibility.engine.EligibilityConditionInput;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.Opportunity;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.opportunity.dto.ConditionRow;
import com.team3.backend.opportunity.dto.OpportunitySummaryRow;
import com.team3.backend.opportunity.dto.RegionCodeRow;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.region.directory.RegionDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.team3.backend.eligibility.exception.EligibilityErrorCode.*;

/**
 * 사용자 한 명 × 정책 하나의 신청 가능성을 계산한다 (저장하지 않음).
 * 정책의 현재 버전 조건·거주지 조건 지역·신청기간을 읽어 엔진에 넘긴다. 기준일은 한국 시간 오늘.
 * 다른 영역(추천·알림)도 이 서비스를 통해 판정 결과를 받는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EligibilityQueryService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final int MAX_PAGE_SIZE = 100;

    private final EligibilityProfileRepository profileRepository;
    private final OpportunityRepository opportunityRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final OpportunityRegionRepository opportunityRegionRepository;
    private final ApplicationWindowRepository windowRepository;
    private final RegionDirectory regionDirectory;
    private final EligibilityEngine engine;
    private final ApplicationStatusCalculator applicationStatusCalculator;

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

        Judgement judgement = judge(profile, versionId, loadInputs(List.of(versionId)), today);
        return EligibilityResponse.of(opportunity.getId(), versionId, judgement.evaluation(),
                judgement.applicationStatus(), now.atZoneSameInstant(KST).toOffsetDateTime());
    }

    public EligibilityListResponse evaluateAll(Long userId, int page, int size) {
        return evaluateAll(userId, page, size, OffsetDateTime.now(KST));
    }

    /**
     * 정규화된 기회를 최신순(id 내림차순)으로 페이지 단위 판정한다. 결과는 저장하지 않는다.
     * 판정에 필요한 컬럼만 조회하고(정규화 스냅샷·evidence 제외), 한 페이지의 조건·지역·신청기간은 버전 ID 묶음으로 한 번씩만 조회한다.
     */
    public EligibilityListResponse evaluateAll(Long userId, int page, int size, OffsetDateTime now) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(INVALID_PAGE_REQUEST, "page=" + page + ", size=" + size);
        }
        EligibilityProfile profile = findProfile(userId);
        LocalDate today = now.atZoneSameInstant(KST).toLocalDate();

        Page<OpportunitySummaryRow> opportunities = opportunityRepository.findNormalizedSummaries(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        List<Long> versionIds = opportunities.stream().map(OpportunitySummaryRow::versionId).toList();
        Inputs inputs = loadInputs(versionIds);

        List<EligibilityListResponse.Item> items = opportunities.stream()
                .map(o -> {
                    Judgement judgement = judge(profile, o.versionId(), inputs, today);
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

    /** 판정 입력을 버전 ID별로 모은다 (조건·거주지 조건 지역·신청기간 각 1회 조회) */
    private Inputs loadInputs(Collection<Long> versionIds) {
        Map<Long, List<EligibilityConditionInput>> conditions = conditionRepository.findRowsByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(ConditionRow::versionId, Collectors.mapping(
                        c -> new EligibilityConditionInput(c.conditionType(), c.requirementType(), c.conditionField(),
                                c.expression(), c.validationStatus()),
                        Collectors.toList())));
        Map<Long, List<String>> residence = opportunityRegionRepository
                .findCodesByVersionIdInAndRole(versionIds, RegionRole.RESIDENCE).stream()
                .collect(Collectors.groupingBy(RegionCodeRow::versionId,
                        Collectors.mapping(RegionCodeRow::regionCode, Collectors.toList())));
        Map<Long, List<ApplicationPeriod>> periods = windowRepository.findRowsByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(ApplicationWindowRow::versionId, Collectors.mapping(
                        w -> new ApplicationPeriod(w.periodType(), w.startDate(), w.endDate(),
                                w.validationStatus(), w.periodText()),
                        Collectors.toList())));
        return new Inputs(conditions, residence, periods);
    }

    private Judgement judge(EligibilityProfile profile, Long versionId, Inputs inputs, LocalDate today) {
        EligibilityEvaluation evaluation = engine.evaluate(profile,
                inputs.conditions().getOrDefault(versionId, List.of()),
                inputs.residence().getOrDefault(versionId, List.of()),
                regionDirectory.index(), today);
        ApplicationStatus applicationStatus = applicationStatusCalculator.calculate(
                inputs.periods().getOrDefault(versionId, List.of()), today);
        return new Judgement(evaluation, applicationStatus);
    }

    private record Inputs(Map<Long, List<EligibilityConditionInput>> conditions, Map<Long, List<String>> residence,
                          Map<Long, List<ApplicationPeriod>> periods) {
    }

    private record Judgement(EligibilityEvaluation evaluation, ApplicationStatus applicationStatus) {
    }
}
