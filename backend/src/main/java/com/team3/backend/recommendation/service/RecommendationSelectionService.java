package com.team3.backend.recommendation.service;

import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.service.EligibilityProfileService;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.GlobalErrorCode;
import com.team3.backend.interest.domain.entity.InterestType;
import com.team3.backend.opportunity.domain.entity.OpportunityType;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository;
import com.team3.backend.recommendation.domain.repository.RecommendationCandidateRepository.TopCandidateRow;
import com.team3.backend.recommendation.dto.InterestMatchLevel;
import com.team3.backend.recommendation.dto.RecommendationCandidate;
import com.team3.backend.recommendation.dto.RecommendationEligibleCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 그룹별 상위 후보를 DB에서 받아 최대 다섯 건을 선정한다.
 * DB(RecommendationCandidateRepository.findTopPerGroup)는 그룹마다 우선순위 상위 LIMIT건만 돌려준다. 혼합 규칙(takeInterested·takeUnrestricted)은
 * 그룹마다 상위 LIMIT건 안에서만 고르므로 결과가 전체 후보를 순회할 때와 같다 (테스트의 전체 순회 참조 구현과 비교해 확인한다).
 * 향후 그룹 안 순서(priority)를 바꾸려면 DB의 상위 후보 선정 기준도 함께 바꿔야 한다.
 */
@Service
@RequiredArgsConstructor
public class RecommendationSelectionService {

    /** 그룹마다 유지하는 후보 수이자 최종 선정 최대 건수 */
    static final int LIMIT = 5;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EligibilityProfileService profileService;
    private final RecommendationCandidateService candidateService;
    private final RecommendationInterestMatcher interestMatcher;
    private final RecommendationCandidateRepository candidateRepository;

    @Transactional(readOnly = true)
    public List<RecommendationEligibleCandidate> select(Long userId) {
        return select(userId, LocalDate.now(KST));
    }

    /** 프로필을 먼저 확인한 뒤 같은 KST 기준일로 그룹별 상위 후보를 조회해 최종 후보를 고른다. */
    @Transactional(readOnly = true)
    public List<RecommendationEligibleCandidate> select(Long userId, LocalDate today) {
        if (today == null) {
            throw new BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE);
        }
        profileService.get(userId);
        var matchContext = interestMatcher.load(userId);
        List<TopCandidateRow> rows = candidateRepository.findTopPerGroup(userId, today, matchContext.unrestricted(),
                matchContext.codes(InterestType.SUB_CATEGORY), matchContext.codes(InterestType.CATEGORY),
                matchContext.codes(InterestType.DETAIL_CATEGORY), LIMIT);
        List<RecommendationCandidate> candidates = candidateService.toCandidates(
                rows.stream().map(TopCandidateRow::row).toList(), today);
        TopCandidates top = new TopCandidates(today);
        for (int i = 0; i < rows.size(); i++) {
            top.add(new RecommendationEligibleCandidate(candidates.get(i), rows.get(i).level(), rows.get(i).status()));
        }
        return top.choose();
    }

    /** 그룹마다 우선순위 상위 LIMIT건만 유지하고 그룹 혼합 규칙으로 최종 후보를 고른다 (운영 경로와 전체 순회 참조 구현 공용). */
    static final class TopCandidates {

        private final Comparator<RecommendationEligibleCandidate> priority;
        private final Map<Group, PriorityQueue<RecommendationEligibleCandidate>> topByGroup = new EnumMap<>(Group.class);

        TopCandidates(LocalDate today) {
            this.priority = priority(today);
        }

        void add(RecommendationEligibleCandidate candidate) {
            PriorityQueue<RecommendationEligibleCandidate> top = topByGroup.computeIfAbsent(groupOf(candidate),
                    ignored -> new PriorityQueue<>(priority.reversed()));
            top.add(candidate);
            if (top.size() > LIMIT) {
                top.poll();
            }
        }

        List<RecommendationEligibleCandidate> choose() {
            if (topByGroup.containsKey(Group.UNRESTRICTED_ELIGIBLE)
                    || topByGroup.containsKey(Group.UNRESTRICTED_POSSIBLE)) {
                return takeUnrestricted(topByGroup, priority);
            }
            return takeInterested(topByGroup, priority);
        }
    }

    private static Group groupOf(RecommendationEligibleCandidate item) {
        if (item.interestMatchLevel() == InterestMatchLevel.UNRESTRICTED) {
            return item.eligibilityStatus() == EligibilityStatus.ELIGIBLE
                    ? Group.UNRESTRICTED_ELIGIBLE : Group.UNRESTRICTED_POSSIBLE;
        }
        if (item.eligibilityStatus() == EligibilityStatus.ELIGIBLE) {
            return item.interestMatchLevel() == InterestMatchLevel.HIGH ? Group.A : Group.B;
        }
        return item.interestMatchLevel() == InterestMatchLevel.HIGH ? Group.C : Group.D;
    }

    private static List<RecommendationEligibleCandidate> takeInterested(
            Map<Group, PriorityQueue<RecommendationEligibleCandidate>> groups,
            Comparator<RecommendationEligibleCandidate> priority) {
        List<RecommendationEligibleCandidate> selected = new ArrayList<>(LIMIT);
        addUpTo(selected, sorted(groups, Group.A, priority), LIMIT);
        int remaining = LIMIT - selected.size();
        List<RecommendationEligibleCandidate> groupB = sorted(groups, Group.B, priority);
        List<RecommendationEligibleCandidate> groupC = sorted(groups, Group.C, priority);
        // 자리가 둘 이상이고 B·C가 모두 있으면 C 한 건을 확보하고 나머지는 B에 우선 배정한다.
        int reservedForC = remaining >= 2 && !groupB.isEmpty() && !groupC.isEmpty() ? 1 : 0;
        addUpTo(selected, groupB, remaining - reservedForC);
        addUpTo(selected, groupC, LIMIT - selected.size());
        if (selected.size() < LIMIT && reservedForC > 0) {
            addUpTo(selected, groupB.subList(Math.min(groupB.size(), remaining - reservedForC), groupB.size()),
                    LIMIT - selected.size());
        }
        addUpTo(selected, sorted(groups, Group.D, priority), LIMIT - selected.size());
        return List.copyOf(selected);
    }

    private static List<RecommendationEligibleCandidate> takeUnrestricted(
            Map<Group, PriorityQueue<RecommendationEligibleCandidate>> groups,
            Comparator<RecommendationEligibleCandidate> priority) {
        List<RecommendationEligibleCandidate> selected = new ArrayList<>(LIMIT);
        addUpTo(selected, sorted(groups, Group.UNRESTRICTED_ELIGIBLE, priority), LIMIT);
        addUpTo(selected, sorted(groups, Group.UNRESTRICTED_POSSIBLE, priority), LIMIT - selected.size());
        return List.copyOf(selected);
    }

    private static List<RecommendationEligibleCandidate> sorted(
            Map<Group, PriorityQueue<RecommendationEligibleCandidate>> groups, Group group,
            Comparator<RecommendationEligibleCandidate> priority) {
        PriorityQueue<RecommendationEligibleCandidate> top = groups.get(group);
        return top == null ? List.of() : top.stream().sorted(priority).toList();
    }

    private static void addUpTo(List<RecommendationEligibleCandidate> selected,
                                List<RecommendationEligibleCandidate> candidates, int count) {
        selected.addAll(candidates.subList(0, Math.min(count, candidates.size())));
    }

    private static Comparator<RecommendationEligibleCandidate> priority(LocalDate today) {
        return Comparator.comparingInt((RecommendationEligibleCandidate item) -> priorityBand(item.candidate(), today))
                .thenComparing(item -> priorityDate(item.candidate()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(item -> isNew(item.candidate(), today) ? 0 : 1)
                .thenComparing(item -> item.candidate().opportunityId(), Comparator.reverseOrder());
    }

    // D-14 마감 또는 D-21~D-7 개강을 먼저 보고, 그 밖에서는 신규 7일 공고를 앞세운다.
    private static int priorityBand(RecommendationCandidate candidate, LocalDate today) {
        LocalDate date = priorityDate(candidate);
        if (date != null) {
            if (candidate.type() == OpportunityType.YOUTH_POLICY
                    && !date.isBefore(today) && !date.isAfter(today.plusDays(14))) {
                return 0;
            }
            if (candidate.type() == OpportunityType.VOCATIONAL_TRAINING
                    && !date.isBefore(today.plusDays(7)) && !date.isAfter(today.plusDays(21))) {
                return 0;
            }
        }
        return isNew(candidate, today) ? 1 : 2;
    }

    private static LocalDate priorityDate(RecommendationCandidate candidate) {
        return candidate.type() == OpportunityType.YOUTH_POLICY
                ? candidate.applicationEndDate() : candidate.trainingStartDate();
    }

    private static boolean isNew(RecommendationCandidate candidate, LocalDate today) {
        if (candidate.createdAt() == null) {
            return false;
        }
        LocalDate createdDate = candidate.createdAt().toLocalDate();
        return !createdDate.isBefore(today.minusDays(7)) && !createdDate.isAfter(today);
    }

    private enum Group { A, B, C, D, UNRESTRICTED_ELIGIBLE, UNRESTRICTED_POSSIBLE }
}
