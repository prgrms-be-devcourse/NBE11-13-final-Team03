package com.team3.backend.auth.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenCacheRepository {

    private final StringRedisTemplate redisTemplate;

    private static final String PREFIX = "refresh-token:";

    public void save(Long userId, String tokenHash, Duration ttl) {
        redisTemplate.opsForValue().set(PREFIX + userId, tokenHash, ttl);
    }

    public Optional<String> findByUserId(Long userId) {
        return Optional.ofNullable(
                redisTemplate.opsForValue()
                        .get(PREFIX + userId)
        );
    }

    public void delete(Long userId) {
        redisTemplate.delete(PREFIX + userId);
    }
}
