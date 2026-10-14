package com.team3.backend.auth.domain.repository;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.user.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByUserId(Long userId);

    void deleteByUserId(Long userId);

}
