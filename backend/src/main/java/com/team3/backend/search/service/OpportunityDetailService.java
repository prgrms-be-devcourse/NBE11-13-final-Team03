package com.team3.backend.search.service;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static com.team3.backend.opportunity.exception.OpportunityErrorCode.OPPORTUNITY_NOT_FOUND;

/** 현재 버전의 공고 상세와 저장된 사용자별 표시 정보만 조회한다. */
@Service
@RequiredArgsConstructor
public class OpportunityDetailService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final OpportunityRepository opportunityRepository;
    private final ApplicationWindowRepository windowRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final OpportunityRegionRepository regionRepository;
    private final YouthPolicyDetailRepository youthPolicyDetailRepository;
    private final TrainingCourseDetailRepository trainingCourseDetailRepository;
    private final ApplicationStatusCalculator applicationStatusCalculator;
    private final OpportunityRegionItemMapper regionItemMapper;
    private final EligibilityProvider eligibilityProvider;
    private final PreferenceService preferenceService;

    /** 미정규화 공고는 공개하지 않고, 로그인한 사용자에게만 최신 판정과 저장 여부를 붙인다. */
    @Transactional(readOnly = true)
    public OpportunityDetailResponseDto get(Long opportunityId, Long userId) {
        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new BusinessException(OPPORTUNITY_NOT_FOUND));
        OpportunityVersion version = opportunity.getCurrentVersion();
        if (version == null) {
            throw new BusinessException(OPPORTUNITY_NOT_FOUND);
        }
        Long versionId = version.getId();
        LocalDate today = LocalDate.now(KST);

        List<ApplicationWindow> windows = windowRepository.findByVersionId(versionId).stream()
                .sorted(Comparator.comparing(ApplicationWindow::getId)).toList();
        List<OpportunityCondition> conditions = conditionRepository.findByVersionId(versionId).stream()
                .sorted(Comparator.comparing(OpportunityCondition::getId)).toList();
        List<OpportunityRegion> regions = regionRepository.findByVersionId(versionId).stream()
                .sorted(Comparator.comparing(OpportunityRegion::getId)).toList();

        // 훈련 시작일은 모집기간이 아니므로 실제 신청 상태는 저장된 신청기간에서만 계산한다.
        ApplicationStatus applicationStatus = applicationStatusCalculator.calculate(windows.stream()
                .map(window -> new ApplicationPeriod(window.getPeriodType(), window.getStartDate(),
                        window.getEndDate(), window.getValidationStatus(), window.getPeriodText()))
                .toList(), today);

        OpportunityDetailResponseDto.Detail detail = switch (opportunity.getOpportunityType()) {
            case YOUTH_POLICY -> youthPolicyDetailRepository.findByVersionId(versionId)
                    .map(OpportunityDetailResponseDto.YouthPolicyDetailItem::from).orElse(null);
            case VOCATIONAL_TRAINING -> trainingCourseDetailRepository.findByVersionId(versionId)
                    .map(OpportunityDetailResponseDto.TrainingCourseDetailItem::from).orElse(null);
        };

        OpportunityDetailResponseDto.EligibilityItem eligibility = null;
        boolean saved = false;
        if (userId != null) {
            eligibility = eligibilityProvider.findFreshEligibility(userId, opportunityId, today)
                    .map(OpportunityDetailResponseDto.EligibilityItem::from).orElse(null);
            saved = preferenceService.getSavedOpportunityIds(userId, List.of(opportunityId)).contains(opportunityId);
        }

        return new OpportunityDetailResponseDto(opportunityId, opportunity.getOpportunityType(),
                opportunity.getTitle(), opportunity.getCategory(), opportunity.getSubCategory(),
                opportunity.getDetailUrl(), opportunity.getCreatedAt(),
                regionItemMapper.toItems(version, conditions, regions),
                windows.stream().map(OpportunityDetailResponseDto.ApplicationWindowItem::from).toList(),
                applicationStatus,
                conditions.stream().map(OpportunityDetailResponseDto.ConditionItem::from).toList(),
                detail, eligibility, saved);
    }

}
