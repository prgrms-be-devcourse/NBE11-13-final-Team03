package com.team3.backend.search.service;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.preference.service.PreferenceService;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
    private final RegionDirectory regionDirectory;
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
                opportunity.getDetailUrl(), opportunity.getCreatedAt(), regionItems(version, conditions, regions),
                windows.stream().map(OpportunityDetailResponseDto.ApplicationWindowItem::from).toList(),
                applicationStatus,
                conditions.stream().map(OpportunityDetailResponseDto.ConditionItem::from).toList(),
                detail, eligibility, saved);
    }

    private List<OpportunityDetailResponseDto.RegionItem> regionItems(
            OpportunityVersion version, List<OpportunityCondition> conditions, List<OpportunityRegion> regions) {
        List<OpportunityDetailResponseDto.RegionItem> items = new ArrayList<>();
        RegionIndex index = regions.isEmpty() ? null : regionDirectory.index();
        for (OpportunityRegion region : regions) {
            String code = region.getRegion().getRegionCode();
            String name = index.find(code).map(RegionIndex.Entry::name).orElse(null);
            ValidationStatus validation = conditions.stream()
                    .filter(condition -> condition.getConditionType() == ConditionType.REGION)
                    .filter(condition -> region.getRegionRole().name().equals(condition.getExpression().get("role")))
                    .map(OpportunityCondition::getValidationStatus).findFirst().orElse(null);
            if (validation == null && region.getRegionRole() == RegionRole.IMPLEMENTATION) {
                validation = implementationValidation(version, code);
            }
            items.add(new OpportunityDetailResponseDto.RegionItem(region.getRegionRole(), "SPECIFIC", code, name,
                    validation));
        }
        // NATIONAL·UNKNOWN에는 지역 행이 없으므로 조건의 검증 상태를 함께 확인한다.
        for (OpportunityCondition condition : conditions) {
            if (condition.getConditionType() != ConditionType.REGION) {
                continue;
            }
            Map<String, Object> expression = condition.getExpression();
            if (!(expression.get("role") instanceof String roleName)
                    || !(expression.get("status") instanceof String status)
                    || (!"NATIONAL".equals(status) && !"UNKNOWN".equals(status))) {
                continue;
            }
            try {
                items.add(new OpportunityDetailResponseDto.RegionItem(RegionRole.valueOf(roleName), status, null, null,
                        condition.getValidationStatus()));
            } catch (IllegalArgumentException ignored) {
                // 미지원 역할은 사용자용 응답에서 확정하지 않는다.
            }
        }
        return List.copyOf(items);
    }

    private ValidationStatus implementationValidation(OpportunityVersion version, String code) {
        // 훈련 시행 지역의 검증 상태는 REGION 조건이 아니라 현재 버전 스냅샷에 보존된다.
        Object regionData = version.getNormalizedSnapshot().get("region");
        if (!(regionData instanceof Map<?, ?> snapshot)
                || !"SPECIFIC".equals(snapshot.get("status"))
                || !(snapshot.get("codes") instanceof List<?> codes) || !codes.contains(code)
                || !(snapshot.get("validationStatus") instanceof String status)) {
            return null;
        }
        try {
            return ValidationStatus.valueOf(status);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
