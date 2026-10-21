package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.ConditionSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConditionSetRepository extends JpaRepository<ConditionSet, Long> {

    Optional<ConditionSet> findByConditionHash(String conditionHash);

    /**
     * 해시에 해당하는 조건 묶음 ID. 없으면 만든다.
     * 정규화는 앱 안에서 직렬로 돌기 때문에(수집 연동은 잠금으로 직렬화) 같은 해시를 동시에 만드는 경우는 고려하지 않는다.
     * 만에 하나 겹치면 유니크 제약 위반으로 그 트랜잭션이 실패하고, 수집 연동의 재처리(대조)가 다시 처리한다.
     */
    default Long resolveId(String conditionHash) {
        return findByConditionHash(conditionHash)
                .orElseGet(() -> save(ConditionSet.builder().conditionHash(conditionHash).build()))
                .getId();
    }
}
