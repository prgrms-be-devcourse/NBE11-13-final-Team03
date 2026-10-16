package com.team3.backend.auth.domain.repository;

import com.team3.backend.auth.domain.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository
        extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByUserIdAndDeviceId(
            Long userId,
            String deviceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select rt
        from RefreshToken rt
        join fetch rt.user
        where rt.sessionId = :sessionId
        """)
    Optional<RefreshToken> findBySessionIdForUpdate(
            @Param("sessionId") String sessionId
    );

    Optional<RefreshToken>
    findFirstByUserIdOrderByLastUsedAtAscCreatedAtAsc(
            Long userId
    );

    List<RefreshToken>
    findAllByUserIdAndExpiresAtLessThanEqual(
            Long userId,
            LocalDateTime expiresAt
    );

    List<RefreshToken> findAllByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByUserIdAndSessionId(
            Long userId,
            String sessionId
    );

    void deleteAllByUserId(Long userId);
}