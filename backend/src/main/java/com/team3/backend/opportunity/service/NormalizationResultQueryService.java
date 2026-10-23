package com.team3.backend.opportunity.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.opportunity.domain.entity.*;
import com.team3.backend.opportunity.domain.repository.*;
import com.team3.backend.opportunity.normalization.dto.NormalizationResultResponse;
import com.team3.backend.region.directory.RegionDirectory;
import com.team3.backend.region.directory.RegionIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.team3.backend.opportunity.exception.OpportunityErrorCode.OPPORTUNITY_NOT_FOUND;

/**
 * 관리자용 정규화 결과 조회 (GET /api/admin/opportunities/{id}/normalization).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NormalizationResultQueryService {

    private final OpportunityRepository opportunityRepository;
    private final OpportunityVersionRepository versionRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final ApplicationWindowRepository windowRepository;
    private final OpportunityRegionRepository opportunityRegionRepository;
    private final OpportunitySourceRepository sourceRepository;
    private final RegionDirectory regionDirectory;

    public NormalizationResultResponse get(Long opportunityId) {
        Opportunity opportunity = opportunityRepository.findById(opportunityId)
                .orElseThrow(() -> new BusinessException(OPPORTUNITY_NOT_FOUND));
        OpportunityVersion current = opportunity.getCurrentVersion();

        List<OpportunityCondition> conditions = current == null ? List.of()
                : conditionRepository.findByVersionId(current.getId()).stream()
                .sorted(Comparator.comparing(OpportunityCondition::getId)).toList();
        List<ApplicationWindow> windows = current == null ? List.of()
                : windowRepository.findByVersionId(current.getId()).stream()
                .sorted(Comparator.comparing(ApplicationWindow::getId)).toList();
        RegionIndex regionIndex = regionDirectory.index();
        List<NormalizationResultResponse.RegionItem> regions = current == null ? List.of()
                : opportunityRegionRepository.findByVersionId(current.getId()).stream()
                .sorted(Comparator.comparing(OpportunityRegion::getId))
                .map(r -> {
                    String code = r.getRegion().getRegionCode();
                    String name = regionIndex.find(code).map(RegionIndex.Entry::name).orElse(null);
                    return new NormalizationResultResponse.RegionItem(code, name, r.getRegionRole());
                })
                .toList();

        List<ValidationStatus> statuses = Stream.concat(
                conditions.stream().map(OpportunityCondition::getValidationStatus),
                windows.stream().map(ApplicationWindow::getValidationStatus)).toList();

        return new NormalizationResultResponse(
                opportunity.getId(),
                opportunity.getOpportunityType(),
                opportunity.getTitle(),
                opportunity.getCategory(),
                opportunity.getDetailUrl(),
                current == null ? null : NormalizationResultResponse.CurrentVersion.from(current),
                conditions.stream().map(NormalizationResultResponse.Condition::from).toList(),
                windows.stream().map(NormalizationResultResponse.Window::from).toList(),
                regions,
                NormalizationResultResponse.ReviewSummary.of(statuses),
                sourceRepository.findByOpportunityIdOrderByIdAsc(opportunityId).stream()
                        .map(NormalizationResultResponse.Source::from).toList(),
                versionRepository.findByOpportunityIdOrderByVersionNoAsc(opportunityId).stream()
                        .map(NormalizationResultResponse.VersionHistory::from).toList());
    }
}
