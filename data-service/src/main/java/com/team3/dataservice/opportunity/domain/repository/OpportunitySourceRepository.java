package com.team3.dataservice.opportunity.domain.repository;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;

import com.team3.dataservice.opportunity.domain.entity.OpportunitySource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OpportunitySourceRepository extends JpaRepository<OpportunitySource, Long> {

    /**
     * 조회 전에 쌓인 INSERT를 자동 flush하지 않는다 (FlushMode COMMIT). 정규화는 원본 묶음(트랜잭션) 끝에 한 번 flush해서 INSERT를 묶어 보내는데,
     * 이 조회가 원본마다 flush를 일으키면 묶음 INSERT가 깨진다. 한 묶음 안에 같은 (출처, 외부 ID)가 두 번 오면 중복 공고가 생겨
     * 유니크 제약(uk_opportunity_sources_source_external)으로 묶음이 실패하고, 처리기가 1건씩 다시 처리한다.
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    Optional<OpportunitySource> findBySourceIdAndExternalId(Long sourceId, String externalId);

    List<OpportunitySource> findByOpportunityIdOrderByIdAsc(Long opportunityId);
}
