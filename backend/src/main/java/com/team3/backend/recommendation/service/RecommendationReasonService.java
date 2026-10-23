package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import com.team3.backend.recommendation.dto.RecommendationReasonsDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 선정된 상태·분류·날짜만으로 결정적인 추천 이유를 만든다. */
@Service
public class RecommendationReasonService {

    /** 신청 가능성, 관심 적합도, 기간 또는 신규 순으로 최대 세 개의 근거를 제공한다. */
    public RecommendationReasonsDto create(RecommendationEligibleCandidate selected, LocalDate today) {
        Objects.requireNonNull(today, "today");
        List<String> badges = new ArrayList<>(3);
        List<String> sentences = new ArrayList<>(2);
        String eligibilityDescription;

        if (selected.eligibilityStatus() == EligibilityStatus.ELIGIBLE) {
            badges.add("신청 조건 충족");
            eligibilityDescription = "저장된 최신 판정에서 신청 조건을 충족합니다";
        } else if (selected.eligibilityStatus() == EligibilityStatus.POSSIBLY_ELIGIBLE) {
            badges.add("신청 조건 확인 필요");
            eligibilityDescription = "확인되지 않은 조건이 있어 신청 가능 여부를 추가로 확인해야 합니다";
        } else {
            throw new IllegalArgumentException("추천 가능한 신청 가능성 상태가 아닙니다.");
        }

        if (selected.interestMatchLevel() == InterestMatchLevel.HIGH) {
            badges.add("관심 분야 일치");
            eligibilityDescription += "; 설정한 관심 분야의 하위 분류와 일치합니다";
        } else if (selected.interestMatchLevel() == InterestMatchLevel.MEDIUM) {
            badges.add("관심 분야 관련");
            eligibilityDescription += "; 설정한 관심 분야의 상위 분류와 관련됩니다";
        }
        sentences.add(eligibilityDescription + ".");

        RecommendationCandidate candidate = selected.candidate();
        String periodReason = periodReason(candidate, today);
        if (periodReason != null) {
            badges.add(periodReason);
            sentences.add(candidate.type() == OpportunityType.YOUTH_POLICY
                    ? "신청 마감까지 " + ChronoUnit.DAYS.between(today, candidate.applicationEndDate()) + "일 남았습니다."
                    : "교육 시작까지 " + ChronoUnit.DAYS.between(today, candidate.trainingStartDate()) + "일 남았습니다.");
        } else if (isNew(candidate, today)) {
            badges.add("신규 공고");
            sentences.add("Y:ON에 최근 등록된 공고입니다.");
        }
        return new RecommendationReasonsDto(List.copyOf(badges), String.join(" ", sentences));
    }

    private static String periodReason(RecommendationCandidate candidate, LocalDate today) {
        if (candidate.type() == OpportunityType.YOUTH_POLICY) {
            LocalDate deadline = candidate.applicationEndDate();
            // 상시 모집이 함께 있으면 일반 기간의 종료일을 공고 전체의 마감일처럼 안내하지 않는다.
            if (!candidate.hasValidAlwaysOpenWindow() && deadline != null
                    && !deadline.isBefore(today) && !deadline.isAfter(today.plusDays(14))) {
                return "마감 D-" + ChronoUnit.DAYS.between(today, deadline);
            }
        } else if (candidate.type() == OpportunityType.VOCATIONAL_TRAINING) {
            LocalDate start = candidate.trainingStartDate();
            if (start != null && !start.isBefore(today.plusDays(7)) && !start.isAfter(today.plusDays(21))) {
                return "교육 시작 D-" + ChronoUnit.DAYS.between(today, start);
            }
        }
        return null;
    }

    private static boolean isNew(RecommendationCandidate candidate, LocalDate today) {
        if (candidate.createdAt() == null) {
            return false;
        }
        LocalDate created = candidate.createdAt().toLocalDate();
        return !created.isBefore(today.minusDays(7)) && !created.isAfter(today);
    }
}
