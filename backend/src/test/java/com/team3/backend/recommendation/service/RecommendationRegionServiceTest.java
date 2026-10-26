package com.team3.backend.recommendation.service;

import com.team3.backend.opportunity.domain.entity.ConditionType;
import com.team3.backend.opportunity.domain.entity.OpportunityCondition;
import com.team3.backend.opportunity.domain.entity.OpportunityRegion;
import com.team3.backend.opportunity.domain.entity.OpportunityVersion;
import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.opportunity.dto.OpportunityVersionRef;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import com.team3.backend.region.domain.entity.Region;
import com.team3.backend.search.dto.OpportunityDetailResponseDto;
import com.team3.backend.search.service.OpportunityRegionItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 선정 결과의 현재 버전 지역을 상세와 같은 의미로 일괄 연결하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class RecommendationRegionServiceTest {

    @Mock private OpportunityRepository opportunityRepository;
    @Mock private OpportunityVersionRepository versionRepository;
    @Mock private OpportunityConditionRepository conditionRepository;
    @Mock private OpportunityRegionRepository regionRepository;
    @Mock private RegionDirectory regionDirectory;

    @Test
    void selectedRegionsKeepMultipleRolesNationalUnknownAndUnspecifiedWithoutPerItemQueries() {
        List<Long> opportunityIds = List.of(1L, 2L, 3L, 4L, 5L);
        List<Long> versionIds = List.of(11L, 12L, 13L, 14L, 15L);
        List<OpportunityVersionRef> refs = List.of(
                new OpportunityVersionRef(1L, 11L), new OpportunityVersionRef(2L, 12L),
                new OpportunityVersionRef(3L, 13L), new OpportunityVersionRef(4L, 14L),
                new OpportunityVersionRef(5L, 15L));
        OpportunityVersion policy = version(11L, Map.of());
        OpportunityVersion national = version(12L, Map.of());
        OpportunityVersion unknown = version(13L, Map.of());
        OpportunityVersion training = version(14L, Map.of("region", Map.of("status", "SPECIFIC",
                "codes", List.of("1100000000"), "validationStatus", "UNVERIFIED")));
        OpportunityVersion unspecified = version(15L, Map.of());
        Region seoul = Region.builder().regionCode("1100000000").regionName("서울").build();
        Region busan = Region.builder().regionCode("2600000000").regionName("부산").build();

        when(opportunityRepository.findCurrentVersionRefs(opportunityIds)).thenReturn(refs);
        when(versionRepository.findAllById(versionIds)).thenReturn(List.of(policy, national, unknown, training, unspecified));
        when(conditionRepository.findByVersionIdIn(versionIds)).thenReturn(List.of(
                condition(1L, policy, "SPECIFIC", RegionRole.RESIDENCE, ValidationStatus.VALID),
                condition(2L, national, "NATIONAL", RegionRole.RESIDENCE, ValidationStatus.VALID),
                condition(3L, unknown, "UNKNOWN", RegionRole.RESIDENCE, ValidationStatus.UNKNOWN),
                condition(4L, training, "NATIONAL", RegionRole.RESIDENCE, ValidationStatus.VALID)));
        when(regionRepository.findByVersionIdIn(versionIds)).thenReturn(List.of(
                region(2L, policy, busan, RegionRole.RESIDENCE),
                region(1L, policy, seoul, RegionRole.RESIDENCE),
                region(3L, training, seoul, RegionRole.IMPLEMENTATION)));
        when(regionDirectory.index()).thenReturn(new RegionIndex(List.of(
                new RegionIndex.Entry("1100000000", "서울", null),
                new RegionIndex.Entry("2600000000", "부산", null))));

        var service = new RecommendationRegionService(opportunityRepository, versionRepository,
                conditionRepository, regionRepository, new OpportunityRegionItemMapper(regionDirectory));
        Map<Long, List<OpportunityDetailResponseDto.RegionItem>> result = service.getRegions(opportunityIds);

        assertEquals(List.of(
                new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "SPECIFIC",
                        "1100000000", "서울", ValidationStatus.VALID),
                new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "SPECIFIC",
                        "2600000000", "부산", ValidationStatus.VALID)), result.get(1L));
        assertEquals(List.of(new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "NATIONAL",
                null, null, ValidationStatus.VALID)), result.get(2L));
        assertEquals(List.of(new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "UNKNOWN",
                null, null, ValidationStatus.UNKNOWN)), result.get(3L));
        assertEquals(List.of(
                new OpportunityDetailResponseDto.RegionItem(RegionRole.IMPLEMENTATION, "SPECIFIC",
                        "1100000000", "서울", ValidationStatus.UNVERIFIED),
                new OpportunityDetailResponseDto.RegionItem(RegionRole.RESIDENCE, "NATIONAL",
                        null, null, ValidationStatus.VALID)), result.get(4L));
        assertEquals(List.of(), result.get(5L));
        verify(opportunityRepository).findCurrentVersionRefs(opportunityIds);
        verify(versionRepository).findAllById(versionIds);
        verify(conditionRepository).findByVersionIdIn(versionIds);
        verify(regionRepository).findByVersionIdIn(versionIds);
    }

    @Test
    void emptySelectionDoesNotQueryRegions() {
        var service = new RecommendationRegionService(opportunityRepository, versionRepository,
                conditionRepository, regionRepository, new OpportunityRegionItemMapper(regionDirectory));

        assertEquals(Map.of(), service.getRegions(List.of()));
        verifyNoInteractions(opportunityRepository, versionRepository, conditionRepository, regionRepository,
                regionDirectory);
    }

    private static OpportunityVersion version(Long id, Map<String, Object> snapshot) {
        return OpportunityVersion.builder().id(id).normalizedSnapshot(snapshot).build();
    }

    private static OpportunityCondition condition(Long id, OpportunityVersion version, String status,
                                                  RegionRole role, ValidationStatus validation) {
        return OpportunityCondition.builder().id(id).version(version).conditionType(ConditionType.REGION)
                .expression(Map.of("status", status, "role", role.name())).validationStatus(validation).build();
    }

    private static OpportunityRegion region(Long id, OpportunityVersion version, Region location, RegionRole role) {
        return OpportunityRegion.builder().id(id).version(version).region(location).regionRole(role).build();
    }
}
