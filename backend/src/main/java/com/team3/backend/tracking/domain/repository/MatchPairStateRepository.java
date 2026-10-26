package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.MatchPairState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MatchPairStateRepository extends JpaRepository<MatchPairState,Long> {
    Optional<MatchPairState> findByUserIdAndOpportunityId(Long userId,Long opportunityId);
}
