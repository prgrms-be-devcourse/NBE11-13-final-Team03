package com.team3.backend.recommendation.service;

import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.opportunity.dto.OpportunityVersionRef;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import com.team3.backend.search.service.OpportunityRegionItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 선정된 공고의 현재 버전 지역 정보를 상세 API와 같은 형식으로 일괄 조회한다. */
@Service
@RequiredArgsConstructor
public class RecommendationRegionService {

    private final OpportunityRepository opportunityRepository;
    private final OpportunityVersionRepository versionRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final OpportunityRegionRepository regionRepository;
    private final OpportunityRegionItemMapper regionItemMapper;

    /** 공고별 추가 조회 없이 현재 버전의 복수 지역과 전국·미확인 조건을 연결한다. */
    @Transactional(readOnly = true)
    public Map<Long, List<OpportunityDetailResponseDto.RegionItem>> getRegions(Collection<Long> opportunityIds) {
        if (opportunityIds.isEmpty()) {
            return Map.of();
        }
        List<OpportunityVersionRef> refs = opportunityRepository.findCurrentVersionRefs(opportunityIds);
        if (refs.isEmpty()) {
            return Map.of();
        }
        List<Long> versionIds = refs.stream().map(OpportunityVersionRef::versionId).toList();
        Map<Long, OpportunityVersion> versions = versionRepository.findAllById(versionIds).stream()
                .collect(Collectors.toMap(OpportunityVersion::getId, Function.identity()));
        Map<Long, List<OpportunityCondition>> conditions = conditionRepository.findByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(condition -> condition.getVersion().getId()));
        Map<Long, List<OpportunityRegion>> regions = regionRepository.findByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(region -> region.getVersion().getId()));

        return refs.stream().filter(ref -> versions.containsKey(ref.versionId()))
                .collect(Collectors.toMap(OpportunityVersionRef::opportunityId, ref -> regionItemMapper.toItems(
                        versions.get(ref.versionId()),
                        conditions.getOrDefault(ref.versionId(), List.of()).stream()
                                .sorted(Comparator.comparing(OpportunityCondition::getId)).toList(),
                        regions.getOrDefault(ref.versionId(), List.of()).stream()
                                .sorted(Comparator.comparing(OpportunityRegion::getId)).toList())));
    }
}
