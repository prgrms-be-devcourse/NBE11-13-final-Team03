package com.team3.notification.domain.repository;

import com.team3.notification.domain.entity.ConsumedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ConsumedMessageRepository extends JpaRepository<ConsumedMessage, Long> {
    Optional<ConsumedMessage> findByMessageId(UUID messageId);
}
