package com.team3.backend.opportunity.domain.repository;

import com.team3.backend.opportunity.domain.entity.ConditionSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConditionSetRepository extends JpaRepository<ConditionSet, Long> {

    Optional<ConditionSet> findByConditionHash(String conditionHash);

    /**
     * 없을 때만 넣는다. 같은 해시를 동시에 넣어도 한쪽은 아무것도 하지 않는다 (유니크 제약 위반 없음).
     * 이 테이블의 유니크 제약은 condition_hash뿐이라 충돌 대상을 따로 적지 않는다 (H2 PostgreSQL 모드 호환)
     */
    @Modifying
    @Query(value = "INSERT INTO condition_sets (condition_hash, created_at) VALUES (:conditionHash, CURRENT_TIMESTAMP) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("conditionHash") String conditionHash);

    /**
     * 해시에 해당하는 조건 묶음 ID. 없으면 만든다. 이미 있는 해시는 유니크 인덱스 조회 1번으로 끝난다.
     * 동시에 같은 해시를 만들면(여러 인스턴스, 정규화와 백필이 겹침) INSERT ... ON CONFLICT DO NOTHING이
     * 먼저 넣은 쪽의 커밋을 기다렸다가 건너뛰고, 다시 조회해 같은 ID를 돌려준다.
     */
    default Long resolveId(String conditionHash) {
        return findByConditionHash(conditionHash).map(ConditionSet::getId).orElseGet(() -> {
            insertIfAbsent(conditionHash);
            return findByConditionHash(conditionHash).orElseThrow().getId();
        });
    }
}
