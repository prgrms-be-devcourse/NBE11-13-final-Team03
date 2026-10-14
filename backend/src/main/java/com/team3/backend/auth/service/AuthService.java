package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.redis.RefreshTokenCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenCacheRepository refreshTokenCacheRepository;

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        refreshTokenCacheRepository.delete(userId);
    }
}
