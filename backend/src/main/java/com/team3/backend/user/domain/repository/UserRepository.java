package com.team3.backend.user.domain.repository;

import com.team3.backend.auth.oauth2.AuthProvider;
import com.team3.backend.user.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByKakaoIdAndProvider(Long kakaoId, AuthProvider provider);
}
