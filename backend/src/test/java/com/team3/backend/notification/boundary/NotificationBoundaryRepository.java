package com.team3.backend.notification.boundary;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationBoundaryRepository extends JpaRepository<NotificationBoundaryEntity, Long> {
}
