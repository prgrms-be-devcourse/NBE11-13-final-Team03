package com.team3.backend.eligibility.service;

import com.team3.backend.eligibility.domain.entity.EligibilityProfile;
import com.team3.backend.eligibility.engine.EligibilityConditionInput;
import com.team3.backend.eligibility.engine.EligibilityEngine;
import com.team3.backend.eligibility.engine.EligibilityEvaluation;
import com.team3.backend.eligibility.engine.EligibilityProfileInput;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.opportunity.dto.ConditionRow;
import com.team3.backend.opportunity.dto.RegionCodeRow;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.region.directory.RegionDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 버전 묶음 판정. 판정에 필요한 컬럼만 조회하고(조건·거주지 조건 지역·신청기간 각 1회) 엔진을 실행한다.
 * 요청 시 판정(EligibilityQueryService)과 판정 결과 저장(EligibilityResultService)이 함께 쓴다. 호출하는 쪽 트랜잭션 안에서 동작한다.
 */
@Component
@RequiredArgsConstructor
public class EligibilityEvaluator {

    private final OpportunityConditionRepository conditionRepository;
    private final OpportunityRegionRepository opportunityRegionRepository;
    private final ApplicationWindowRepository windowRepository;
    private final RegionDirectory regionDirectory;
    private final EligibilityEngine engine;
    private final ApplicationStatusCalculator applicationStatusCalculator;

    public record Judgement(EligibilityEvaluation evaluation, ApplicationStatus applicationStatus) {
    }

    /** 버전 ID → 판정 결과. 요청한 버전마다 결과가 하나씩 있다 (조건이 없으면 엔진이 UNKNOWN) */
    public Map<Long, Judgement> judge(EligibilityProfile profile, Collection<Long> versionIds, LocalDate today) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
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

        var index = regionDirectory.index();
        EligibilityProfileInput input = EligibilityProfileInput.from(profile);
        Map<Long, Judgement> result = new HashMap<>();
        for (Long versionId : versionIds) {
            EligibilityEvaluation evaluation = engine.evaluate(input, conditions.getOrDefault(versionId, List.of()),
                    residence.getOrDefault(versionId, List.of()), index, today);
            ApplicationStatus applicationStatus = applicationStatusCalculator.calculate(
                    periods.getOrDefault(versionId, List.of()), today);
            result.put(versionId, new Judgement(evaluation, applicationStatus));
        }
        return result;
    }
}
