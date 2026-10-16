package com.team3.backend.auth.service;

import com.team3.backend.auth.domain.entity.RefreshToken;
import com.team3.backend.auth.domain.repository.RefreshTokenRepository;
import com.team3.backend.auth.exception.AuthErrorCode;
import com.team3.backend.auth.jwt.*;
import com.team3.backend.auth.session.AuthSessionProperties;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.user.domain.entity.User;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuthSessionProperties sessionProperties;

    public record TokenPair(
            String accessToken,
            String refreshToken
    ) {}

    @Transactional
    public TokenPair issueToken(
            User user,
            String deviceId
    ) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException(
                    "deviceId must not be blank"
            );
        }

        User lockedUser = userRepository
                .findByIdForUpdate(user.getId())
                .orElseThrow(() ->
                        new BusinessException(
                                UserErrorCode.USER_NOT_FOUND
                        )
                );

        removeExpiredSessions(lockedUser.getId());

        Optional<RefreshToken> existingSession =
                refreshTokenRepository
                        .findByUserIdAndDeviceId(
                                lockedUser.getId(),
                                deviceId
                        );

        String sessionId = existingSession
                .map(RefreshToken::getSessionId)
                .orElseGet(() -> UUID.randomUUID().toString());

        if (existingSession.isEmpty()) {
            evictOldestSessionIfNecessary(
                    lockedUser.getId()
            );
        }

        TokenPair tokenPair =
                generateTokenPair(
                        lockedUser,
                        sessionId
                );

        String tokenHash =
                refreshTokenHasher.hash(
                        tokenPair.refreshToken()
                );

        LocalDateTime expiresAt =
                LocalDateTime.now().plus(
                        jwtProperties.getRefreshTokenValidity()
                );

        if (existingSession.isPresent()) {
            RefreshToken session =
                    existingSession.get();

            session.rotate(
                    tokenHash,
                    expiresAt
            );

            refreshTokenRepository.saveAndFlush(session);
        } else {
            RefreshToken newSession =
                    RefreshToken.builder()
                            .user(lockedUser)
                            .sessionId(sessionId)
                            .deviceId(deviceId)
                            .tokenHash(tokenHash)
                            .expiresAt(expiresAt)
                            .lastUsedAt(LocalDateTime.now())
                            .build();

            refreshTokenRepository.saveAndFlush(newSession);
        }

        return tokenPair;
    }

    @Transactional
    public TokenPair reissueToken(String refreshToken) {
        validateRefreshToken(refreshToken);

        Long userId =
                tokenProvider.getUserId(refreshToken);

        String sessionId =
                tokenProvider.getSessionId(refreshToken);

        RefreshToken storedToken =
                getAndValidateStoredRefreshTokenForUpdate(
                        userId,
                        sessionId,
                        refreshToken
                );

        TokenPair newTokenPair =
                generateTokenPair(
                        storedToken.getUser(),
                        sessionId
                );

        rotateRefreshToken(
                storedToken,
                newTokenPair.refreshToken()
        );

        return newTokenPair;
    }

    public TokenPair generateTokenPair(
            User user,
            String sessionId
    ) {
        String accessToken =
                tokenProvider.generateToken(
                        user,
                        sessionId,
                        jwtProperties.getAccessTokenValidity(),
                        TokenType.ACCESS
                );

        String refreshToken =
                tokenProvider.generateToken(
                        user,
                        sessionId,
                        jwtProperties.getRefreshTokenValidity(),
                        TokenType.REFRESH
                );

        return new TokenPair(
                accessToken,
                refreshToken
        );
    }

    private void removeExpiredSessions(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        List<RefreshToken> expiredSessions =
                refreshTokenRepository
                        .findAllByUserIdAndExpiresAtLessThanEqual(
                                userId,
                                now
                        );

        if (expiredSessions.isEmpty()) {
            return;
        }

        refreshTokenRepository.deleteAll(expiredSessions);
        refreshTokenRepository.flush();
    }

    private void evictOldestSessionIfNecessary(
            Long userId
    ) {
        long activeSessionCount =
                refreshTokenRepository.countByUserId(userId);

        if (activeSessionCount
                < sessionProperties.getMaxActiveSessions()) {
            return;
        }

        RefreshToken oldestSession =
                refreshTokenRepository
                        .findFirstByUserIdOrderByLastUsedAtAscCreatedAtAsc(
                                userId
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "활성 세션 수와 세션 조회 결과가 일치하지 않습니다."
                                )
                        );

        refreshTokenRepository.delete(oldestSession);
        refreshTokenRepository.flush();
    }

    private void validateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(
                    AuthErrorCode.REFRESH_TOKEN_NOT_FOUND
            );
        }

        TokenStatus tokenStatus =
                tokenProvider.validateToken(
                        refreshToken,
                        TokenType.REFRESH
                );

        if (tokenStatus == TokenStatus.EXPIRED) {
            throw new BusinessException(
                    AuthErrorCode.EXPIRED_REFRESH_TOKEN
            );
        }

        if (tokenStatus != TokenStatus.VALID) {
            throw new BusinessException(
                    AuthErrorCode.INVALID_REFRESH_TOKEN
            );
        }
    }

    private RefreshToken
    getAndValidateStoredRefreshTokenForUpdate(
            Long userId,
            String sessionId,
            String rawRefreshToken
    ) {
        RefreshToken storedToken =
                refreshTokenRepository
                        .findBySessionIdForUpdate(sessionId)
                        .orElseThrow(() ->
                                new BusinessException(
                                        AuthErrorCode
                                                .REFRESH_TOKEN_NOT_FOUND
                                )
                        );

        if (!storedToken.getUser()
                .getId()
                .equals(userId)) {
            throw new BusinessException(
                    AuthErrorCode.REFRESH_TOKEN_MISMATCH
            );
        }

        if (!storedToken.getExpiresAt()
                .isAfter(LocalDateTime.now())) {
            throw new BusinessException(
                    AuthErrorCode.EXPIRED_REFRESH_TOKEN
            );
        }

        if (!refreshTokenHasher.matches(
                storedToken.getTokenHash(),
                rawRefreshToken
        )) {
            throw new BusinessException(
                    AuthErrorCode.REFRESH_TOKEN_MISMATCH
            );
        }

        return storedToken;
    }

    private void rotateRefreshToken(
            RefreshToken storedToken,
            String newRefreshToken
    ) {
        String newTokenHash =
                refreshTokenHasher.hash(newRefreshToken);

        LocalDateTime expiresAt =
                LocalDateTime.now().plus(
                        jwtProperties.getRefreshTokenValidity()
                );

        storedToken.rotate(
                newTokenHash,
                expiresAt
        );

        refreshTokenRepository.saveAndFlush(storedToken);
    }
}