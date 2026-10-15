package com.team3.backend.auth.domain.repository;

import com.team3.backend.auth.domain.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select rt
        from RefreshToken rt
        join fetch rt.user
        where rt.user.id = :userId
        """)
    Optional<RefreshToken> findByUserIdForUpdate(
            @Param("userId") Long userId
    );

    void deleteByUserId(Long userId);
}
