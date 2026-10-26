package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.MatchCandidateEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import com.team3.backend.tracking.domain.entity.MatchPairState;
import java.util.List;

public interface MatchCandidateEventRepository extends JpaRepository<MatchCandidateEvent,Long> {
    List<MatchCandidateEvent> findByPairIdOrderById(Long pairId);
    List<MatchCandidateEvent> findByPairUserIdAndReasonAndStatusOrderById(Long userId, MatchPairState.Reason reason, String status, Pageable page);
}
