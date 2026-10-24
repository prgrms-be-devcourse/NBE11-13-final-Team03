package com.team3.backend.outbox.domain.repository;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
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
