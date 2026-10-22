package com.team3.backend.recommendation.service;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.opportunity.domain.entity.PeriodType;
import com.team3.backend.opportunity.domain.entity.ValidationStatus;
import com.team3.backend.opportunity.domain.repository.ApplicationWindowRepository;
import com.team3.backend.opportunity.domain.repository.TrainingCourseDetailRepository;
import com.team3.backend.opportunity.dto.ApplicationWindowRow;
import com.team3.backend.opportunity.dto.TrainingScheduleRow;
import com.team3.backend.opportunity.normalization.ApplicationPeriod;
import com.team3.backend.opportunity.normalization.ApplicationStatus;
import com.team3.backend.opportunity.normalization.ApplicationStatusCalculator;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository.CandidateRow;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 추천 기본 후보를 순회하고 현재 버전의 모집·훈련 날짜를 보강한다. */
@Service
@RequiredArgsConstructor
public class RecommendationCandidateService {

    private final RecommendationCandidateRepository candidateRepository;
    private final ApplicationWindowRepository windowRepository;
    private final TrainingCourseDetailRepository trainingDetailRepository;
    private final ApplicationStatusCalculator statusCalculator;

    /** 첫 커서는 0이며, 순회 시작 시 계산한 KST 날짜를 모든 배치에 동일하게 넘긴다. */
    @Transactional(readOnly = true)
    public List<RecommendationCandidate> getCandidatesAfter(long afterId, int size, LocalDate today) {
        if (afterId < 0 || size < 1 || today == null) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
        List<CandidateRow> rows = candidateRepository.findAfter(afterId, size, today);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> versionIds = rows.stream().map(CandidateRow::versionId).toList();
        List<Long> trainingVersions = versionIds(rows, OpportunityType.VOCATIONAL_TRAINING);
        Map<Long, List<ApplicationWindowRow>> windows = windowRepository.findRowsByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(ApplicationWindowRow::versionId));
        Map<Long, TrainingScheduleRow> training = trainingVersions.isEmpty() ? Map.of()
                : trainingDetailRepository.findScheduleRows(trainingVersions).stream()
                .collect(Collectors.toMap(TrainingScheduleRow::versionId, Function.identity()));

        return rows.stream().map(row -> {
            List<ApplicationWindowRow> periods = windows.getOrDefault(row.versionId(), List.of());
            ApplicationStatus status = statusCalculator.calculate(periods.stream()
                    .map(window -> new ApplicationPeriod(window.periodType(), window.startDate(),
                            window.endDate(), window.validationStatus(), window.periodText()))
                    .toList(), today);
            if (row.type() == OpportunityType.YOUTH_POLICY) {
                return new RecommendationCandidate(row.opportunityId(), row.type(), row.title(),
                        row.category(), row.subCategory(), row.detailCategory(), status,
                        nearestOpenDeadline(periods, today), null, row.createdAt());
            }
            TrainingScheduleRow schedule = training.get(row.versionId());
            return new RecommendationCandidate(row.opportunityId(), row.type(), row.title(),
                    row.category(), row.subCategory(), row.detailCategory(), status,
                    null, schedule.trainingStartDate(), row.createdAt());
        }).toList();
    }

    private static List<Long> versionIds(Collection<CandidateRow> rows, OpportunityType type) {
        return rows.stream().filter(row -> row.type() == type).map(CandidateRow::versionId).toList();
    }

    private static LocalDate nearestOpenDeadline(List<ApplicationWindowRow> windows, LocalDate today) {
        return windows.stream()
                .filter(window -> window.validationStatus() == ValidationStatus.VALID
                        && window.periodType() == PeriodType.FIXED
                        && window.endDate() != null && !window.endDate().isBefore(today)
                        && (window.startDate() == null || !window.startDate().isAfter(today)))
                .map(ApplicationWindowRow::endDate)
                .min(Comparator.naturalOrder()).orElse(null);
    }
}
