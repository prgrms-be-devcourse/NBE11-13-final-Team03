package com.team3.backend.outbox.domain.repository;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Query("select o.dedupKey from OutboxEvent o where o.dedupKey in :dedupKeys")
    Set<String> findExistingDedupKeys(@Param("dedupKeys") Collection<String> dedupKeys);
}
