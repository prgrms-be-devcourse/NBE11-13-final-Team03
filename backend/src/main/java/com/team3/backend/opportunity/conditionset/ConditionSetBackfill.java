package com.team3.backend.opportunity.conditionset;

import com.team3.backend.opportunity.domain.entity.RegionRole;
import com.team3.backend.opportunity.domain.repository.ConditionSetRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityConditionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityRegionRepository;
import com.team3.backend.opportunity.domain.repository.OpportunityVersionRepository;
import com.team3.backend.opportunity.dto.ConditionRow;
import com.team3.backend.opportunity.dto.RegionCodeRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 조건 묶음이 비어 있는 기회 버전에 조건 묶음을 채운다 (condition_set_id 컬럼이 생기기 전에 만든 버전).
 * 앱이 시작되면 한 번 돌고, 채울 버전이 없으면 쿼리 한 번으로 끝난다. 새로 정규화하는 버전은 정규화가 직접 채운다.
 * 키는 저장된 조건 행과 거주지역(RESIDENCE) 행으로 만들어, 정규화 때와 같은 값이 나온다 (ConditionSetKey).
 * 1,000건 묶음마다 커밋한다. yon.normalization.condition-set-backfill.enabled=false면 끈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yon.normalization.condition-set-backfill.enabled", havingValue = "true",
        matchIfMissing = true)
public class ConditionSetBackfill {

    private static final int CHUNK = 1_000;

    private final OpportunityVersionRepository versionRepository;
    private final OpportunityConditionRepository conditionRepository;
    private final OpportunityRegionRepository regionRepository;
    private final ConditionSetRepository conditionSetRepository;
    private final PlatformTransactionManager transactionManager;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("Condition set backfill failed (판정 결과 저장은 조건 묶음 없는 버전을 건너뛸 수 있음)", e);
        }
    }

    /** @return 조건 묶음을 채운 버전 수 */
    public int run() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long started = System.nanoTime();
        int assigned = 0;
        long afterId = 0L;
        while (true) {
            long cursor = afterId;
            List<Long> versionIds = tx.execute(status ->
                    versionRepository.findIdsWithoutConditionSet(cursor, PageRequest.of(0, CHUNK)));
            if (versionIds == null || versionIds.isEmpty()) {
                break;
            }
            Integer count = tx.execute(status -> assign(versionIds));
            assigned += count == null ? 0 : count;
            afterId = versionIds.getLast();
            if (versionIds.size() < CHUNK) {
                break;
            }
        }
        if (assigned > 0) {
            log.info("Condition sets backfilled: versions={}, elapsedMs={}", assigned,
                    (System.nanoTime() - started) / 1_000_000);
        }
        return assigned;
    }

    private int assign(List<Long> versionIds) {
        Map<Long, List<ConditionRow>> conditions = conditionRepository.findRowsByVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(ConditionRow::versionId));
        Map<Long, List<String>> residence = regionRepository
                .findCodesByVersionIdInAndRole(versionIds, RegionRole.RESIDENCE).stream()
                .collect(Collectors.groupingBy(RegionCodeRow::versionId,
                        Collectors.mapping(RegionCodeRow::regionCode, Collectors.toList())));

        Map<String, List<Long>> byHash = new LinkedHashMap<>();
        for (Long versionId : versionIds) {
            String hash = ConditionSetKey.hashRows(conditions.getOrDefault(versionId, List.of()),
                    residence.getOrDefault(versionId, List.of()));
            byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(versionId);
        }
        int assigned = 0;
        for (Map.Entry<String, List<Long>> e : byHash.entrySet()) {
            assigned += versionRepository.assignConditionSet(conditionSetRepository.resolveId(e.getKey()), e.getValue());
        }
        return assigned;
    }
}
