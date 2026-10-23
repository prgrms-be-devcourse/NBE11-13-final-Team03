package com.team3.backend.eligibility.api.local;

import com.team3.backend.eligibility.api.dto.OpportunityInfo;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRepository;
import com.team3.backend.opportunity.domain.repository.TrainingCourseDetailRepository;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.opportunity.dto.OpportunityInfoRow;
import com.team3.backend.opportunity.dto.TrainingScheduleRow;
import com.team3.backend.opportunity.domain.status.ApplicationPeriod;
import com.team3.backend.opportunity.domain.status.ApplicationStatusCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 기회 ID 목록 → 추천용 기회 요약. 쿼리 3번 (기회, 신청기간, 훈련 상세) */
@Component
@RequiredArgsConstructor
public class OpportunityInfoAssembler {

    private final OpportunityRepository opportunityRepository;
    private final ApplicationWindowRepository windowRepository;
    private final TrainingCourseDetailRepository trainingDetailRepository;
    private final ApplicationStatusCalculator applicationStatusCalculator;

    /** 기회 ID → 요약. 정규화되지 않은 기회는 빠진다 */
    Map<Long, OpportunityInfo> assemble(Collection<Long> opportunityIds, LocalDate today) {
        if (opportunityIds.isEmpty()) {
            return Map.of();
        }
        List<OpportunityInfoRow> rows = opportunityRepository.findInfoRows(opportunityIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> versionIds = rows.stream().map(OpportunityInfoRow::versionId).toList();
        Map<Long, List<ApplicationWindowRow>> windows = windowRepository.findRowsByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(ApplicationWindowRow::versionId));
        Map<Long, TrainingScheduleRow> training = trainingDetailRepository.findScheduleRows(versionIds).stream()
                .collect(Collectors.toMap(TrainingScheduleRow::versionId, Function.identity()));

        Map<Long, OpportunityInfo> result = new HashMap<>();
        for (OpportunityInfoRow row : rows) {
            List<ApplicationWindowRow> ws = windows.getOrDefault(row.versionId(), List.of());
            TrainingScheduleRow t = training.get(row.versionId());
            result.put(row.opportunityId(), new OpportunityInfo(
                    row.opportunityId(),
                    row.opportunityType(),
                    row.title(),
                    row.category(),
                    row.subCategory(),
                    t == null ? null : t.ncsCode(),
                    applicationStatusCalculator.calculate(ws.stream().map(OpportunityInfoAssembler::toPeriod).toList(), today),
                    nextDeadline(ws, today),
                    t == null ? null : t.trainingStartDate(),
                    row.createdAt(),
                    row.detailUrl()));
        }
        return result;
    }

    /** 오늘 이후로 끝나는 유효한 고정 신청기간 중 가장 이른 종료일 */
    static LocalDate nextDeadline(List<ApplicationWindowRow> windows, LocalDate today) {
        return windows.stream()
                .filter(w -> w.validationStatus() == ValidationStatus.VALID && w.periodType() == PeriodType.FIXED)
                .map(ApplicationWindowRow::endDate)
                .filter(end -> end != null && !end.isBefore(today))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static ApplicationPeriod toPeriod(ApplicationWindowRow w) {
        return new ApplicationPeriod(w.periodType(), w.startDate(), w.endDate(), w.validationStatus(), w.periodText());
    }
}
