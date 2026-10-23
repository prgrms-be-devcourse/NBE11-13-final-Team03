package com.team3.notification.domain.repository;

import com.team3.notification.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByUserIdAndSourceEventId(Long userId, UUID sourceEventId);
}
