package com.team3.backend.outbox.domain.repository;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    Optional<OutboxEvent> findByDedupKey(String dedupKey);

    /** Payload is the complete envelope, so the business deadline is nested under payload. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update outbox_events set status = 'SKIPPED', last_error = 'POLICY_DEADLINE_CHANGED'
            where opportunity_id = :opportunityId and event_type = 'NOTIFICATION_REQUESTED'
              and notification_type = 'DEADLINE_SOON' and status = 'PENDING'
              and payload #>> '{payload,applicationEndDate}' = :previousEndDate
            """, nativeQuery = true)
    int cancelPendingDeadlineNotifications(@Param("opportunityId") Long opportunityId,
                                          @Param("previousEndDate") String previousEndDate);

    @Modifying
    @Query("""
            update OutboxEvent o set o.status = com.team3.backend.outbox.domain.entity.OutboxStatus.SKIPPED,
                o.lastError = 'USER_WITHDRAWN'
            where o.userId = :userId and o.eventType = 'NOTIFICATION_REQUESTED'
                and o.status = com.team3.backend.outbox.domain.entity.OutboxStatus.PENDING
            """)
    int cancelPendingNotifications(@Param("userId") Long userId);

    @Query("select o.dedupKey from OutboxEvent o where o.dedupKey in :dedupKeys")
    Set<String> findExistingDedupKeys(@Param("dedupKeys") Collection<String> dedupKeys);
}
