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
import java.util.Collection;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /** Always lock users in ID order before preferences, matching withdrawal/preference lock order. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id in :userIds order by u.id")
    List<User> lockNotificationRecipients(@Param("userIds") Collection<Long> userIds);

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
