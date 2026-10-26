package com.team3.dataservice.opportunity.conditionset;

import com.team3.dataservice.opportunity.domain.repository.ConditionSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 조건 묶음 해시 → ID 메모리 캐시. 정규화는 원본마다 묶음 ID를 찾는데 묶음은 1,700개 정도뿐이라 매번 DB에 묻지 않는다
 * (실데이터 14만 건 적재에서 조회 14만 번, 25초 — ingest 튜닝 E3).
 * 커밋된 ID만 캐시한다: 트랜잭션 안에서 처음 찾은 ID는 커밋 뒤에 넣는다 (롤백된 새 묶음 ID가 남지 않게). 조건 묶음은 지우지 않으므로 무효화는 없다.
 */
@Component
@RequiredArgsConstructor
public class ConditionSetIdCache {

    private final ConditionSetRepository conditionSetRepository;
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    /** ConditionSetRepository.resolveId와 같다 (없으면 만든다) */
    public Long resolve(String conditionHash) {
        Long cached = ids.get(conditionHash);
        if (cached != null) {
            return cached;
        }
        Long id = conditionSetRepository.resolveId(conditionHash);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ids.put(conditionHash, id);
                }
            });
        } else {
            ids.put(conditionHash, id);
        }
        return id;
    }
}
