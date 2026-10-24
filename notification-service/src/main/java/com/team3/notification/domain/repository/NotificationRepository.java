package com.team3.notification.domain.repository;

import com.team3.notification.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByUserIdAndSourceEventId(Long userId, UUID sourceEventId);

    @Query("select n from Notification n where n.userId = :userId and n.inAppVisible = true and n.id < :beforeId order by n.id desc")
    List<Notification> page(@Param("userId") long userId, @Param("beforeId") long beforeId, Pageable pageable);

    long countByUserIdAndReadAtIsNullAndInAppVisibleTrue(Long userId);
    boolean existsByIdAndUserIdAndInAppVisibleTrue(Long id, Long userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.inAppVisible = true and n.id = :id and n.readAt is null")
    int markRead(@Param("userId") long userId, @Param("id") long id, @Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.inAppVisible = true and n.readAt is null")
    int markAllRead(@Param("userId") long userId, @Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.clickedAt = :now where n.userId = :userId and n.inAppVisible = true and n.id = :id and n.clickedAt is null")
    int markClicked(@Param("userId") long userId, @Param("id") long id, @Param("now") Instant now);
}
