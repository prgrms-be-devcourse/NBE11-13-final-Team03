package com.team3.dataservice.outbox.domain.repository;

import com.team3.dataservice.outbox.domain.entity.CollectionOutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionOutboxEventRepository extends JpaRepository<CollectionOutboxEvent, Long> {
    /** Flush the job transition first; a duplicate must not mark its transaction rollback-only. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into collection_outbox_events
                (message_id, dedup_key, event_type, sync_job_id, payload, status,
                 retry_count, next_retry_at, created_at)
            values (:messageId, :dedupKey, 'COLLECTION_COMPLETED', :jobId, cast(:payload as jsonb),
                    'PENDING', 0, :createdAt, :createdAt)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("messageId") UUID messageId, @Param("dedupKey") String dedupKey,
                       @Param("jobId") Long jobId, @Param("payload") String payload,
                       @Param("createdAt") Instant createdAt);
}
