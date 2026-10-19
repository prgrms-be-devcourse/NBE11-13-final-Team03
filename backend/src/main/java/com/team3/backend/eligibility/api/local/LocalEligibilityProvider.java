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
import com.team3.backend.eligibility.exception.EligibilityErrorCode;
import com.team3.backend.eligibility.service.EligibilityResultService;
import com.team3.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        Map<Long, EligibilityResult> byId = resultService.refresh(userId, ids).stream()
                .collect(Collectors.toMap(EligibilityResult::getOpportunityId, Function.identity()));
        List<EligibilityResult> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        return withOpportunities(ordered);
    }

    @Override
    public EligibilitySnapshotPage findByStatuses(Long userId, Collection<EligibilityStatus> statuses, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(EligibilityErrorCode.INVALID_PAGE_REQUEST);
        }
        resultService.refreshAll(userId);
        Page<EligibilityResult> results = resultRepository.findByUserIdAndEligibilityStatusIn(userId, statuses,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "opportunityId")));
        return new EligibilitySnapshotPage(withOpportunities(results.getContent()),
                page, size, results.getTotalElements(), results.hasNext());
    }

    private List<EligibilitySnapshot> withOpportunities(List<EligibilityResult> results) {
        Map<Long, OpportunityInfo> infos = opportunityInfoAssembler.assemble(
                results.stream().map(EligibilityResult::getOpportunityId).toList(), LocalDate.now(KST));
        return results.stream().map(r -> toSnapshot(r, infos.get(r.getOpportunityId()))).toList();
    }

    static EligibilitySnapshot toSnapshot(EligibilityResult r, OpportunityInfo opportunity) {
        return new EligibilitySnapshot(
                r.getUserId(),
                r.getOpportunityId(),
                opportunity,
                r.getEligibilityStatus(),
                r.getReason(),
                r.getConditionResults().stream().map(LocalEligibilityProvider::toCondition).toList(),
                r.getOpportunityVersionId(),
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
