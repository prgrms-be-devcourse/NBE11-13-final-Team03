package com.team3.backend.search.service;

import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 상세와 추천 응답에서 현재 버전의 지역 역할·상태를 동일하게 표현한다. */
@Component
@RequiredArgsConstructor
public class OpportunityRegionItemMapper {

    private final RegionDirectory regionDirectory;

    /** 지역 행이 없는 전국·미확인 상태도 조건에 저장된 그대로 전달한다. */
    public List<OpportunityDetailResponseDto.RegionItem> toItems(
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
