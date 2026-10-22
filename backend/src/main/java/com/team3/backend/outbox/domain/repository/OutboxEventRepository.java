package com.team3.backend.outbox.domain.repository;

import com.team3.backend.outbox.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    boolean existsByDedupKey(String dedupKey);
}
