package com.team3.backend.eligibility.api.local;

import com.team3.backend.eligibility.api.EligibilityProvider;
import com.team3.backend.eligibility.api.dto.ConditionResult;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshot;
import com.team3.backend.eligibility.api.dto.EligibilitySnapshotPage;
import com.team3.backend.eligibility.api.dto.OpportunityInfo;
import com.team3.backend.eligibility.domain.entity.ConditionMatchResult;
import com.team3.backend.eligibility.domain.entity.EligibilityResult;
import com.team3.backend.eligibility.domain.entity.EligibilityStatus;
import com.team3.backend.eligibility.domain.repository.EligibilityResultRepository;
import com.team3.backend.eligibility.dto.EligibilityStatusRow;
import com.team3.backend.eligibility.dto.OpportunityEligibility;
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 같은 애플리케이션 안에서 정책 엔진을 직접 호출하는 구현 (yon.eligibility.provider=local, 기본값).
 */
@Component
@ConditionalOnProperty(prefix = "yon.eligibility", name = "provider", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
@Transactional
public class LocalEligibilityProvider implements EligibilityProvider {

    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EligibilityResultService resultService;
    private final EligibilityResultRepository resultRepository;
    private final OpportunityInfoAssembler opportunityInfoAssembler;

    @Override
    public List<EligibilitySnapshot> getEligibilities(Long userId, Collection<Long> opportunityIds) {
        List<Long> ids = opportunityIds.stream().distinct().toList();
        Map<Long, OpportunityEligibility> byId = resultService.refresh(userId, ids).stream()
                .collect(Collectors.toMap(OpportunityEligibility::opportunityId, Function.identity()));
        List<OpportunityEligibility> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        return withOpportunities(ordered);
    }

    @Override
    public EligibilitySnapshotPage findByStatuses(Long userId, Collection<EligibilityStatus> statuses, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(EligibilityErrorCode.INVALID_PAGE_REQUEST);
        }
        resultService.refreshAll(userId);
        Page<OpportunityEligibility> results = resultRepository.findByStatuses(userId, statuses,
                PageRequest.of(page, size));
        return new EligibilitySnapshotPage(withOpportunities(results.getContent()),
                page, size, results.getTotalElements(), results.hasNext());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, EligibilityStatus> findFreshStatuses(Long userId, Collection<Long> opportunityIds, LocalDate today) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(opportunityIds, "opportunityIds");
        Objects.requireNonNull(today, "today");
        List<Long> ids = opportunityIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resultRepository.findFreshStatuses(userId, ids, EligibilityResultService.RULE_VERSION, today).stream()
                .collect(Collectors.toUnmodifiableMap(EligibilityStatusRow::opportunityId, EligibilityStatusRow::status));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EligibilitySnapshot> findFreshEligibility(Long userId, Long opportunityId, LocalDate today) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(opportunityId, "opportunityId");
        Objects.requireNonNull(today, "today");
        return resultRepository.findFresh(userId, opportunityId, EligibilityResultService.RULE_VERSION, today)
                .map(r -> withOpportunities(List.of(r), today).getFirst());
    }

    private List<EligibilitySnapshot> withOpportunities(List<OpportunityEligibility> results) {
        return withOpportunities(results, LocalDate.now(KST));
    }

    private List<EligibilitySnapshot> withOpportunities(List<OpportunityEligibility> results, LocalDate today) {
        Map<Long, OpportunityInfo> infos = opportunityInfoAssembler.assemble(
                results.stream().map(OpportunityEligibility::opportunityId).toList(), today);
        return results.stream().map(e -> toSnapshot(e, infos.get(e.opportunityId()))).toList();
    }

    /** 기회 하나의 결과. 판정 결과는 조건 묶음 단위라, 기회 ID·기회 버전은 결과가 아니라 기회의 현재 값이다 */
    static EligibilitySnapshot toSnapshot(OpportunityEligibility e, OpportunityInfo opportunity) {
        EligibilityResult r = e.result();
        return new EligibilitySnapshot(
                r.getUserId(),
                e.opportunityId(),
                opportunity,
                r.getEligibilityStatus(),
                r.getReason(),
                r.getConditionResults().stream().map(LocalEligibilityProvider::toCondition).toList(),
                e.opportunityVersionId(),
                r.getProfileVersion(),
                r.getRuleVersion(),
                r.getValidUntil(),
                r.getEvaluatedAt());
    }

    private static ConditionResult toCondition(Map<String, Object> m) {
        return new ConditionResult(
                (String) m.get("type"),
                (String) m.get("field"),
                (String) m.get("requirement"),
                ConditionMatchResult.valueOf((String) m.get("result")),
                (String) m.get("reason"),
                m.get("expected"),
                m.get("actual"));
    }
}
