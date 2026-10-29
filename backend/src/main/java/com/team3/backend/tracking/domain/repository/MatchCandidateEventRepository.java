package com.team3.backend.tracking.domain.repository;

import com.team3.backend.tracking.domain.entity.MatchCandidateEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MatchCandidateEventRepository extends JpaRepository<MatchCandidateEvent,Long> {
    List<MatchCandidateEvent> findByPairIdOrderById(Long pairId);
    /** Daily summary members (alias c): every first match plus transitions caused by the user's own profile change. PostgreSQL JSON operator. */
    String SUMMARY_MEMBER_SQL="(c.reason='FIRST_MATCH' OR (c.reason='TRANSITION' AND c.evidence->>'trigger'='PROFILE_CHANGED'))";
    @Query(value="SELECT c.* FROM notification_match_candidates c JOIN notification_match_pairs p ON p.id=c.pair_id "
            + "WHERE p.user_id=:userId AND c.status='PENDING' AND "+SUMMARY_MEMBER_SQL+" ORDER BY c.id LIMIT :limit",nativeQuery=true)
    List<MatchCandidateEvent> findPendingSummaryMembers(long userId,int limit);
}
