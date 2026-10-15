package com.team3.backend.user.domain.repository;

import com.team3.backend.auth.oauth2.AuthProvider;
import com.team3.backend.user.domain.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByKakaoIdAndProvider(
            Long kakaoId,
            AuthProvider provider
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u
        from User u
        where u.id = :userId
        """)
    Optional<User> findByIdForUpdate(
            @Param("userId") Long userId
    );
}